"""Small COS adapter: private STANDARD objects, checksummed reads, no delete API."""
from contextlib import contextmanager
from hashlib import sha256
import json
import os
from pathlib import Path
import re


class ArchiveError(ValueError):
    pass


def digest(data):
    return sha256(data).hexdigest()


def check_hash(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{64}', value):
        raise ArchiveError('invalid digest')
    return value


def file_hash(path):
    h = sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


class CosBackend:
    def __init__(self, config, credentials):
        from qcloud_cos import CosConfig, CosS3Client
        path = Path(credentials)
        info = path.lstat()
        if path.is_symlink() or info.st_mode & 0o077 or info.st_uid != os.getuid():
            raise ArchiveError('credentials require current owner and mode 0600')
        value = json.loads(path.read_bytes())
        self.bucket = config['bucket']
        self.prefix = config['prefix'].rstrip('/') + '/'
        if not self.prefix.startswith('coinstrategy/') or '..' in self.prefix:
            raise ArchiveError('prefix must be under coinstrategy/')
        self.identity = json.dumps({k: config[k] for k in ('bucket', 'region', 'prefix')}, sort_keys=True)
        self.client = CosS3Client(CosConfig(
            Region=config['region'], SecretId=value['SecretId'], SecretKey=value['SecretKey'],
            Scheme='https', Timeout=60))

    def _key(self, key):
        if key.startswith('/') or '..' in key or '\\' in key:
            raise ArchiveError('unsafe object key')
        return self.prefix + key

    @contextmanager
    def stream(self, key, start=None, length=None):
        args = {'Bucket': self.bucket, 'Key': self._key(key)}
        if start is not None:
            args['Range'] = f'bytes={start}-{start + length - 1}'
        result = self.client.get_object(**args)
        stream = result['Body'].get_raw_stream()
        try:
            yield stream
        finally:
            stream.close()

    def publish(self, key, path):
        """Only missing keys are PUT. Existing or new content is fully GET-verified."""
        from qcloud_cos.cos_exception import CosServiceError
        expected = file_hash(path)
        try:
            self.client.head_object(Bucket=self.bucket, Key=self._key(key))
        except CosServiceError as exc:
            if exc.get_status_code() != 404:
                raise
            with Path(path).open('rb') as body:
                self.client.put_object(Bucket=self.bucket, Key=self._key(key), Body=body,
                    StorageClass='STANDARD', ServerSideEncryption='AES256',
                    Metadata={'x-cos-meta-sha256': expected})
        h = sha256()
        with self.stream(key) as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                h.update(block)
        if h.hexdigest() != expected:
            raise ArchiveError('remote content hash mismatch')
        return expected

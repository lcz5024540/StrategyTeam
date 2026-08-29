# Funding Paper service deployment

This service is separate from the HTX execution service. It binds only to `127.0.0.1:8083`,
uses the `test,funding` profiles, and has no live order client.

On the server, place the jar, `start-funding-paper.sh`, and `funding.env` under
`/opt/coinstrategy/funding-paper`; then install `coin-strategy-funding-paper.service` under
`/etc/systemd/system/`. The startup script sources the existing shared secret file only for the
database password and decryptor key. It creates the configured replay directory before startup.

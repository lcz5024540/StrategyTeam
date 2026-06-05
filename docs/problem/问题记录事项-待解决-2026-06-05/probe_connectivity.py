#!/usr/bin/env python3
"""2026-06-03 巡检：服务器/数据库可达性探测（只读，无写操作）。
背景：本期沙箱无法直连 49.51.250.174:3306，需取证区分是「服务器/防火墙问题」还是「沙箱网络问题」。"""
import socket, time, urllib.request
HOST = "49.51.250.174"
PORTS = [22, 80, 443, 3306, 8080]
def probe(host, port, t=6):
    s = socket.socket(); s.settimeout(t); t0 = time.time()
    try:
        s.connect((host, port)); return f"OPEN ({time.time()-t0:.1f}s)"
    except Exception as e:
        return f"FAIL {e.__class__.__name__} ({time.time()-t0:.1f}s)"
    finally:
        s.close()
print("egress public IP:", urllib.request.urlopen("https://api.ipify.org", timeout=10).read().decode())
print("internet baseline github:443 ->", probe("github.com", 443))
print("internet baseline pypi:443   ->", probe("pypi.org", 443))
for p in PORTS:
    print(f"{HOST}:{p} -> {probe(HOST, p)}")

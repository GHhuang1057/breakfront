#!/usr/bin/env python3
"""一次性 RCON 客户端：给本地 dev 服发控制台指令（配合 ops 授权）。"""
import socket
import struct
import sys


def rcon(host, port, password, *commands):
    s = socket.create_connection((host, port), timeout=10)

    def send(req_id, ptype, payload):
        body = struct.pack("<ii", req_id, ptype) + payload.encode("utf-8") + b"\x00\x00"
        s.sendall(struct.pack("<i", len(body)) + body)
        hdr = s.recv(4)
        while len(hdr) < 4:
            hdr += s.recv(4 - len(hdr))
        (ln,) = struct.unpack("<i", hdr)
        data = b""
        while len(data) < ln:
            data += s.recv(ln - len(data))
        rid, rtype = struct.unpack("<ii", data[:8])
        return rid, rtype, data[8:-2].decode("utf-8", "replace")

    send(1, 3, password)  # auth
    for cmd in commands:
        rid, rtype, text = send(2, 2, cmd)
        print(f">> {cmd}\n<< {text.strip()}")
    s.close()


if __name__ == "__main__":
    rcon("127.0.0.1", 25575, "breakfront_dev", *sys.argv[1:])

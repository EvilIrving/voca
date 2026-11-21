#!/usr/bin/env python3
"""局域网 HTTP CONNECT 转发，用于让真机借道本机网络访问实时语音服务。"""
import socket
import sys
import threading


def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for item in (src, dst):
            try:
                item.close()
            except OSError:
                pass


def handle(conn):
    head = b""
    while b"\r\n\r\n" not in head:
        chunk = conn.recv(4096)
        if not chunk:
            conn.close()
            return
        head += chunk
    try:
        method, target, _ = head.split(b"\r\n", 1)[0].decode().split()
    except ValueError:
        conn.close()
        return
    if method != "CONNECT":
        conn.sendall(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n")
        conn.close()
        return
    host, _, port = target.partition(":")
    try:
        upstream = socket.create_connection((host, int(port or 443)), timeout=12)
    except OSError:
        conn.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
        conn.close()
        return
    conn.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
    threading.Thread(target=pipe, args=(conn, upstream), daemon=True).start()
    pipe(upstream, conn)


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8889
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", port))
    server.listen(64)
    print(f"connect-proxy listening on {port}", flush=True)
    while True:
        conn, _ = server.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()

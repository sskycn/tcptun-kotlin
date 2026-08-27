#!/usr/bin/env python3
"""Small RFC 1928/1929 UDP-associate probe used by real-device validation."""

import argparse
import os
import socket
import struct


def read_exact(sock, size):
    data = b""
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise RuntimeError("SOCKS server closed the control connection")
        data += chunk
    return data


def read_address(sock, atyp):
    if atyp == 1:
        host = socket.inet_ntoa(read_exact(sock, 4))
    elif atyp == 3:
        host = read_exact(sock, read_exact(sock, 1)[0]).decode("ascii")
    elif atyp == 4:
        host = socket.inet_ntop(socket.AF_INET6, read_exact(sock, 16))
    else:
        raise RuntimeError("invalid SOCKS address type")
    return host, struct.unpack("!H", read_exact(sock, 2))[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("host")
    parser.add_argument("port", type=int)
    parser.add_argument("username", nargs="?", default="")
    parser.add_argument("password", nargs="?", default="")
    args = parser.parse_args()
    user = args.username.encode()
    password = args.password.encode()
    method = 2 if user or password else 0
    transaction = os.urandom(2)
    query = transaction + b"\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + b"\x07example\x03com\x00\x00\x01\x00\x01"

    with socket.create_connection((args.host, args.port), timeout=10) as control:
        control.settimeout(10)
        control.sendall(bytes((5, 1, method)))
        if read_exact(control, 2) != bytes((5, method)):
            raise RuntimeError("SOCKS authentication method was rejected")
        if method == 2:
            control.sendall(bytes((1, len(user))) + user + bytes((len(password),)) + password)
            if read_exact(control, 2) != b"\x01\x00":
                raise RuntimeError("SOCKS username/password was rejected")
        control.sendall(b"\x05\x03\x00\x01\x00\x00\x00\x00\x00\x00")
        version, status, _, atyp = read_exact(control, 4)
        if version != 5 or status != 0:
            raise RuntimeError("SOCKS UDP associate was rejected")
        relay_host, relay_port = read_address(control, atyp)
        if relay_host in ("0.0.0.0", "::"):
            relay_host = args.host
        packet = b"\x00\x00\x00\x01" + socket.inet_aton("1.1.1.1") + struct.pack("!H", 53) + query
        family = socket.AF_INET6 if ":" in relay_host else socket.AF_INET
        with socket.socket(family, socket.SOCK_DGRAM) as udp:
            udp.settimeout(10)
            udp.sendto(packet, (relay_host, relay_port))
            response, _ = udp.recvfrom(65535)
            if len(response) < 12:
                raise RuntimeError("invalid SOCKS UDP response")
            if transaction not in response:
                raise RuntimeError("DNS transaction ID did not match")


if __name__ == "__main__":
    main()

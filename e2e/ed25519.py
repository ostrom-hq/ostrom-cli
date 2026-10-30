# SPDX-License-Identifier: Apache-2.0
"""Ed25519 signing (RFC 8032, section 5.1) in plain Python, for the e2e contract server only: it signs the gate policy
the hooks verify, with a test key, without a cryptography package on the runner. Slow and not constant-time: never
use it for a real key.
"""
import base64
import hashlib

P = 2 ** 255 - 19
L = 2 ** 252 + 27742317777372353535851937790883648493
D = -121665 * pow(121666, P - 2, P) % P
SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")


def _inverse(x):
    return pow(x, P - 2, P)


def _add(a, b):
    first = (a[1] - a[0]) * (b[1] - b[0]) % P
    second = (a[1] + a[0]) * (b[1] + b[0]) % P
    third = 2 * a[3] * b[3] * D % P
    fourth = 2 * a[2] * b[2] % P
    e, f, g, h = second - first, fourth - third, fourth + third, second + first
    return e * f % P, g * h % P, f * g % P, e * h % P


def _multiply(scalar, point):
    result = (0, 1, 1, 0)
    while scalar > 0:
        if scalar & 1:
            result = _add(result, point)
        point = _add(point, point)
        scalar >>= 1
    return result


def _recover_x(y, sign):
    x2 = (y * y - 1) * _inverse(D * y * y + 1)
    x = pow(x2, (P + 3) // 8, P)
    if (x * x - x2) % P != 0:
        x = x * pow(2, (P - 1) // 4, P) % P
    return P - x if (x & 1) != sign else x


_GY = 4 * _inverse(5) % P
_GX = _recover_x(_GY, 0)
BASE = (_GX, _GY, 1, _GX * _GY % P)


def _compress(point):
    z = _inverse(point[2])
    x, y = point[0] * z % P, point[1] * z % P
    return int.to_bytes(y | ((x & 1) << 255), 32, "little")


def _expand(seed):
    digest = hashlib.sha512(seed).digest()
    scalar = int.from_bytes(digest[:32], "little")
    scalar &= (1 << 254) - 8
    scalar |= 1 << 254
    return scalar, digest[32:]


def public_key(seed):
    """The 32-byte public key of a 32-byte seed."""
    return _compress(_multiply(_expand(seed)[0], BASE))


def public_key_pem(seed):
    """The public key as a SPKI PEM, as `GET /api/v1/instance/keys` serves it."""
    body = base64.b64encode(SPKI_PREFIX + public_key(seed)).decode()
    return f"-----BEGIN PUBLIC KEY-----\n{body}\n-----END PUBLIC KEY-----\n"


def sign(seed, message):
    """The 64-byte signature of [message] by the key of [seed]."""
    scalar, prefix = _expand(seed)
    public = _compress(_multiply(scalar, BASE))
    nonce = int.from_bytes(hashlib.sha512(prefix + message).digest(), "little") % L
    commitment = _compress(_multiply(nonce, BASE))
    challenge = int.from_bytes(hashlib.sha512(commitment + public + message).digest(), "little") % L
    return commitment + int.to_bytes((nonce + challenge * scalar) % L, 32, "little")

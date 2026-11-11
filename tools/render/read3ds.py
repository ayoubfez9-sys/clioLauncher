"""Minimal .3DS reader: objects (name, vertices, faces, per-face material, uvs, local matrix) + materials."""
import struct

def read3ds(path):
    data = open(path, 'rb').read()
    objects, materials = [], {}

    def cstr(o):
        e = data.index(b'\0', o)
        return data[o:e].decode('latin-1'), e + 1

    def color(o, end):
        # returns first color sub-chunk (0x0010 float rgb / 0x0011 byte rgb / 0x0012,0x0013 gamma)
        while o < end:
            cid, ln = struct.unpack_from('<HI', data, o)
            if cid in (0x0011, 0x0012):
                return tuple(b / 255 for b in data[o + 6:o + 9])
            if cid in (0x0010, 0x0013):
                return struct.unpack_from('<3f', data, o + 6)
            o += ln
        return None

    def pct(o, end):
        while o < end:
            cid, ln = struct.unpack_from('<HI', data, o)
            if cid == 0x0030: return struct.unpack_from('<H', data, o + 6)[0] / 100
            if cid == 0x0031: return struct.unpack_from('<f', data, o + 6)[0] / 100
            o += ln
        return None

    def walk(o, end, ctx):
        while o + 6 <= end:
            cid, ln = struct.unpack_from('<HI', data, o)
            if ln < 6: break
            body, cend = o + 6, o + ln
            if cid in (0x4D4D, 0x3D3D):
                walk(body, cend, ctx)
            elif cid == 0x4000:
                name, p = cstr(body)
                ob = {'name': name, 'verts': [], 'faces': [], 'fmat': [], 'uv': [], 'matrix': None, 'smooth': None}
                walk(p, cend, ob)
                if ob['verts']: objects.append(ob)
            elif cid == 0x4100:
                walk(body, cend, ctx)
            elif cid == 0x4110:
                n = struct.unpack_from('<H', data, body)[0]
                ctx['verts'] = [struct.unpack_from('<3f', data, body + 2 + 12 * i) for i in range(n)]
            elif cid == 0x4140:
                n = struct.unpack_from('<H', data, body)[0]
                ctx['uv'] = [struct.unpack_from('<2f', data, body + 2 + 8 * i) for i in range(n)]
            elif cid == 0x4160:
                ctx['matrix'] = struct.unpack_from('<12f', data, body)
            elif cid == 0x4120:
                n = struct.unpack_from('<H', data, body)[0]
                ctx['faces'] = [struct.unpack_from('<3H', data, body + 2 + 8 * i) for i in range(n)]
                p = body + 2 + 8 * n
                while p + 6 <= cend:
                    sid, sln = struct.unpack_from('<HI', data, p)
                    if sid == 0x4150:
                        ctx['smooth'] = struct.unpack_from('<%dI' % n, data, p + 6)
                    if sid == 0x4130:
                        mname, q = cstr(p + 6)
                        cnt = struct.unpack_from('<H', data, q)[0]
                        idx = struct.unpack_from('<%dH' % cnt, data, q + 2)
                        ctx['fmat'].append((mname, idx))
                    p += sln
            elif cid == 0xAFFF:
                m = {'name': None, 'diffuse': None, 'specular': None, 'shin': None, 'transp': 0, 'tex': None}
                p = body
                while p + 6 <= cend:
                    sid, sln = struct.unpack_from('<HI', data, p)
                    if sid == 0xA000: m['name'] = cstr(p + 6)[0]
                    elif sid == 0xA020: m['diffuse'] = color(p + 6, p + sln)
                    elif sid == 0xA030: m['specular'] = color(p + 6, p + sln)
                    elif sid == 0xA040: m['shin'] = pct(p + 6, p + sln)
                    elif sid == 0xA050: m['transp'] = pct(p + 6, p + sln) or 0
                    elif sid == 0xA200:
                        q = p + 6
                        while q + 6 <= p + sln:
                            tid, tln = struct.unpack_from('<HI', data, q)
                            if tid == 0xA300: m['tex'] = cstr(q + 6)[0]
                            q += tln
                    p += sln
                materials[m['name']] = m
            o = cend

    walk(0, len(data), {})
    return objects, materials

if __name__ == '__main__':
    import sys
    obs, mats = read3ds(sys.argv[1])
    print(len(obs), 'objects,', len(mats), 'materials')
    for ob in obs:
        xs = [v[0] for v in ob['verts']]; ys = [v[1] for v in ob['verts']]; zs = [v[2] for v in ob['verts']]
        ms = sorted(set(m for m, _ in ob['fmat']))
        print('%-28s v=%6d f=%6d  x[%7.1f,%7.1f] y[%7.1f,%7.1f] z[%7.1f,%7.1f]  %s' % (ob['name'], len(ob['verts']), len(ob['faces']), min(xs), max(xs), min(ys), max(ys), min(zs), max(zs), ','.join(ms)[:80]))
    for k, m in mats.items():
        print('MAT', k, m)

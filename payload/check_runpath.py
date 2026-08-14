import struct, sys
sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.abspath(__file__)))
d = open(sys.argv[1], 'rb').read()
e_phoff = struct.unpack_from('<Q', d, 32)[0]
e_phentsize = struct.unpack_from('<H', d, 54)[0]
e_phnum = struct.unpack_from('<H', d, 56)[0]
dynoff = None
for k in range(e_phnum):
    off = e_phoff + k * e_phentsize
    if struct.unpack_from('<I', d, off)[0] == 2:
        dynoff = struct.unpack_from('<Q', d, off + 8)[0]
        break
strtab = None
paths = {}
j = 0
while True:
    tag, val = struct.unpack_from('<QQ', d, dynoff + j * 16)
    if tag == 0:
        break
    if tag == 5:
        strtab = val
    if tag in (15, 29):
        paths['RUNPATH' if tag == 29 else 'RPATH'] = val
    j += 1
for k, v in paths.items():
    print(k, '=', d[strtab + v:strtab + v + 200].split(b'\0')[0].decode())
if not paths:
    print('no RUNPATH/RPATH')

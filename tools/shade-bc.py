"""Relocate bundled BC classes and reflective names away from Android's boot classes."""
import pathlib, zipfile, struct
root=pathlib.Path(__file__).resolve().parent.parent/'libs'
dest=root/'shaded'
dest.mkdir(exist_ok=True)
def relocate(b):
    return b.replace(b'org/bouncycastle',b'org/jellylegacy/crypto').replace(b'org.bouncycastle',b'org.jellylegacy.crypto')
def clazz(data):
    count=struct.unpack_from('>H',data,8)[0]; pos=10; out=bytearray(data[:10]); i=1
    sizes={3:4,4:4,5:8,6:8,7:2,8:2,9:4,10:4,11:4,12:4,15:3,16:2,17:4,18:4,19:2,20:2}
    while i<count:
        tag=data[pos];pos+=1;out.append(tag)
        if tag==1:
            n=struct.unpack_from('>H',data,pos)[0];pos+=2
            value=relocate(data[pos:pos+n]);pos+=n
            out+=struct.pack('>H',len(value))+value
        else:
            n=sizes[tag];out+=data[pos:pos+n];pos+=n
            if tag in (5,6):i+=1
        i+=1
    out+=data[pos:]
    return bytes(out)
for name in ['bcprov','bctls','bcutil']:
    with zipfile.ZipFile(root/(name+'.jar')) as source,zipfile.ZipFile(dest/(name+'.jar'),'w',zipfile.ZIP_DEFLATED) as target:
        for entry in source.infolist():
            path=entry.filename
            if path.startswith('META-INF/versions/') or path.endswith(('.SF','.RSA','.DSA')) or path=='META-INF/MANIFEST.MF':continue
            data=source.read(entry)
            if path.endswith('.class'):data=clazz(data)
            elif path.startswith('META-INF/services/'):data=relocate(data)
            target.writestr(relocate(path.encode()).decode(),data)
    print('Relocated '+name)

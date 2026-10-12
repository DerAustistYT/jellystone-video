import pathlib,urllib.request,hashlib
root=pathlib.Path(__file__).resolve().parent.parent/'libs';root.mkdir(exist_ok=True)
dependencies={'bcprov': 'fc50334d4d87b4272e72fa95ca748ead05bdef02ef760a5b13f64ffee317cd81', 'bctls': 'c0dbcd24ceeb78a263661a38d243a3c82620f002a41f49c5399a9635764a963a', 'bcutil': 'e72ef8a0fcad295207529b268ee02eb72acc7c83ec2e3a9df90cbed9380e3990'}
for name,expected in dependencies.items():
    target=root/(name+'.jar')
    if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest()!=expected:
        url='https://repo.maven.apache.org/maven2/org/bouncycastle/'+name+'-jdk15to18/1.86/'+name+'-jdk15to18-1.86.jar'
        data=urllib.request.urlopen(url,timeout=60).read()
        if hashlib.sha256(data).hexdigest()!=expected:raise IOError('Dependency checksum mismatch: '+name)
        target.write_bytes(data)
    print('Verified '+name)

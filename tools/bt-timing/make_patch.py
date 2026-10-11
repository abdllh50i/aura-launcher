"""Build a small, reproducible delta; the manufacturer's executable is never distributed.

python tools/bt-timing/make_patch.py --stock ../dl/goc/gocsdk_8800 --ndk E:/Android/Sdk/ndk/27.1.12297006
"""
import argparse, base64, hashlib, json, pathlib, struct, subprocess
ROOT = pathlib.Path(__file__).resolve().parents[2]
HERE = pathlib.Path(__file__).resolve().parent
STOCK_SHA256 = '8e2fbf23d24021e5b57ef0dc5df851570ccad621b0f8cb82433d3090ce010ce7'
def build(stock, ndk, output):
    b = bytearray(pathlib.Path(stock).read_bytes())
    original = hashlib.sha256(b).hexdigest()
    if original != STOCK_SHA256:
        raise ValueError('Different GOC driver: this delta only supports the reviewed K2501 AIC8800 executable')
    clang = pathlib.Path(ndk)/'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
    linker = clang.with_name('ld.lld.exe')
    objcopy = clang.with_name('llvm-objcopy.exe')
    out = ROOT/'out/bt-timing'
    out.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(clang), '--target=armv7a-linux-androideabi21', '-c', str(HERE/'query.S'), '-o', str(out/'query.o')], check=True)
    subprocess.run([str(linker), '-T', str(HERE/'link.ld'), str(out/'query.o'), '-o', str(out/'query.elf')], check=True)
    for section in ['hook','reporthook','text']:
        subprocess.run([str(objcopy), '-O','binary','--only-section=.'+section,str(out/'query.elf'),str(out/(section+'.bin'))], check=True)
    hook=(out/'hook.bin').read_bytes();payload=(out/'text.bin').read_bytes()
    assert len(hook)==4
    # These are the reviewed stock instructions and report literal. Refuse a different executable.
    assert b[0x89d0c:0x89d10].hex() == '80b56f46'
    assert b[0x8709c:0x8709e].hex() == '1b48'
    at=(len(b)+4095)&~4095
    records=[]
    def change(offset,data):
        records.append({'offset':offset,'data':base64.b64encode(data).decode()})
        if offset+len(data)>len(b):b.extend(bytes(offset+len(data)-len(b)))
        b[offset:offset+len(data)]=data
    # Repurpose the optional PT_NOTE program header for a new read/execute segment after the stock BSS.
    ph=struct.unpack_from('<I',b,28)[0]+8*32
    assert struct.unpack_from('<I',b,ph)[0]==4
    change(ph,struct.pack('<8I',1,at,0x180000,0x180000,len(payload),len(payload),5,4096))
    change(at,payload)
    change(0x89d0c,hook)
    # The stock sender wrongly requires a UI template which this platform never configures.
    change(0x8709c,(out/'reporthook.bin').read_bytes()) # supply MQ template instead of dereferencing the absent one
    result={'stockSha256':original,'patchedSha256':hashlib.sha256(b).hexdigest(),'size':len(b),'records':records}
    pathlib.Path(output).parent.mkdir(parents=True,exist_ok=True)
    pathlib.Path(output).write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    (out/'gocsdk_8800').write_bytes(b)
    print('delta:',original,'->',result['patchedSha256'],'payload',len(payload))
    return result
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--stock',required=True);p.add_argument('--ndk',required=True)
    p.add_argument('--output',default=str(ROOT/'app/src/main/assets/bt-timing/patch.json'))
    a=p.parse_args();build(a.stock,a.ndk,a.output)

"""Execute the patched ARM instructions with Unicorn at two ASLR bases.

The original GOC AV/C packet builder runs unchanged. Memory allocation/queue
and UI formatting helpers are substituted; no phone or physical UART is simulated.
python tools/bt-timing/test_patch.py --stock ../dl/goc/gocsdk_8800 --deps ../dl/bt-analysis
"""
import argparse, base64, hashlib, json, pathlib, struct, sys
p=argparse.ArgumentParser();p.add_argument('--stock',required=True);p.add_argument('--deps',required=True)
a=p.parse_args();sys.path.insert(0,a.deps)
from unicorn import Uc, UC_ARCH_ARM, UC_MODE_THUMB, UC_HOOK_CODE, UC_HOOK_MEM_INVALID
from unicorn.arm_const import *
from elftools.elf.elffile import ELFFile
root=pathlib.Path(__file__).resolve().parents[2]
patch=json.loads((root/'app/src/main/assets/bt-timing/patch.json').read_text())
b=bytearray(pathlib.Path(a.stock).read_bytes())
assert hashlib.sha256(b).hexdigest()==patch['stockSha256']
b.extend(bytes(patch['size']-len(b)))
for r in patch['records']:
    d=base64.b64decode(r['data']);b[r['offset']:r['offset']+len(d)]=d
assert hashlib.sha256(b).hexdigest()==patch['patchedSha256']
def read32(u,a):return struct.unpack('<I',u.mem_read(a,4))[0]
def put32(u,a,v):u.mem_write(a,struct.pack('<I',v&0xffffffff))
def cstr(u,a):return bytes(u.mem_read(a,256)).split(b'\0')[0]
def signed(v):return v if v<0x80000000 else v-0x100000000
def case(base, connected=True, report=False, total=240123, pos=73125):
    u=Uc(UC_ARCH_ARM,UC_MODE_THUMB);u.mem_map(base,0x1c0000)
    # Program headers, including the new executable segment.
    for i in range(10):
        typ,off,va,pa,fs,ms,flags,align=struct.unpack_from('<8I',b,52+i*32)
        if typ==1:u.mem_write(base+va,bytes(b[off:off+fs]))
    stock=ELFFile(open(a.stock,'rb'))
    for rel in stock.get_section_by_name('.rel.dyn').iter_relocations():
        if rel['r_info_type']==23:
            adr=base+rel['r_offset'];put32(u,adr,read32(u,adr)+base)
        elif rel['r_info_type']==21:
            name=stock.get_section_by_name('.dynsym').get_symbol(rel['r_info_sym']).name
            if name=='__stack_chk_guard':put32(u,base+rel['r_offset'],base+0x1a0f00)
    heap=base+0x1a0000;sp=base+0x1b8000;stop=base+0x1bff00
    state={'heap':heap+0x1000,'packets':[],'reports':[],'cached':0}
    put32(u,base+0x16b090,heap);u.mem_write(heap+0x1a0,b'\x32\0')
    u.mem_write(base+0x165048,bytes([int(connected)]));u.mem_write(base+0x15ab7a,b'\x77\0')
    regs=[UC_ARM_REG_R0,UC_ARM_REG_R1,UC_ARM_REG_R2,UC_ARM_REG_R3]
    def ret(value=None):
        if value is not None:u.reg_write(UC_ARM_REG_R0,value)
        u.reg_write(UC_ARM_REG_PC,u.reg_read(UC_ARM_REG_LR))
    def hook(u,adr,size,_):
        r0,r1,r2,r3=[u.reg_read(r) for r in regs];s=u.reg_read(UC_ARM_REG_SP)
        at=adr-base
        if adr==stop:u.emu_stop()
        elif at==0x82160:
            out=state['heap'];state['heap']+=(r0+15)&~15;u.mem_write(out,bytes(r0));ret(out)
        elif at==0x8220c:ret()
        elif at==0xbce4c:u.mem_write(r0,bytes(u.mem_read(r1,r2)));ret(r0)
        elif at==0x82aa0:
            assert (r0,r1)==(0x77,0x11b),(hex(r0),hex(r1))
            data=read32(u,r2+8)
            state['packets'].append(bytes(u.mem_read(data,13)));ret()
        elif at==0x150e50:u.mem_write(r0,bytes(r1));ret(r0)
        elif at==0x1514e0:
            out=cstr(u,r1);u.mem_write(r0,out+b'\0');ret(len(out))
        elif at==0x150e00:ret(0) # Android logging
        elif at==0x84404:state['cached']+=1;ret() # preserve stock VI cached-state callback
        elif at==0x856f0:
            assert cstr(u,r2)==b'%d%c%d%c%d'
            sep1,p1,sep2,status=struct.unpack('<4I',u.mem_read(s,16))
            assert sep1==sep2==255
            out=str(signed(r3)).encode()+b'\xff'+str(signed(p1)).encode()+b'\xff'+str(status).encode()
            u.mem_write(r0,out+b'\0');ret(len(out))
        elif at==0x834b0:
            assert cstr(u,r0)==b'MQ[playstatus_data]'
            assert cstr(u,r1)==b'playstatus_data'
            out=b'MQ'+cstr(u,r2);u.mem_write(r0,out+b'\0');ret()
        elif at==0x8b58c:state['reports'].append(cstr(u,r0));ret()
        elif at==0x150df0:raise AssertionError('stack guard failed')
    u.hook_add(UC_HOOK_CODE,hook)
    def bad(u,access,addr,size,value,_):
        print('invalid memory',hex(addr),'pc',hex(u.reg_read(UC_ARM_REG_PC)),'lr',hex(u.reg_read(UC_ARM_REG_LR)))
        return False
    u.hook_add(UC_HOOK_MEM_INVALID,bad)
    u.reg_write(UC_ARM_REG_SP,sp);u.reg_write(UC_ARM_REG_LR,stop|1)
    if report:
        # The unchanged GetPlayStatus response adapter calls the patched sender.
        u.mem_write(heap,struct.pack('<II',total&0xffffffff,pos&0xffffffff)+b'\x01')
        u.reg_write(UC_ARM_REG_R0,heap);start=base+0xa1e4c
    else:start=base+0x89d0c
    u.emu_start(start|1,stop,count=10000)
    return state
for base in [0x1000000,0x4000000]:
    q=case(base)
    assert q['packets']==[bytes.fromhex('10110e01480000195830000000')],q
    assert q['cached']==1
    disconnected=case(base,connected=False)
    assert not disconnected['packets'] and disconnected['cached']==1
    r=case(base,report=True)
    assert r['reports']==[b'MQ240123\xff73125\xff1'],r
    assert not case(base,report=True,total=0)['reports']
    assert not case(base,report=True,total=0xffffffff)['reports']
    assert not case(base,report=True,pos=0xffffffff)['reports']
print('PASS: GetPlayStatus packet; stock VI callback; disconnected guard; MQ duration/position; zero/unknown values; SHA-256; two ASLR bases')

"""Execute the real BRF ARM64 configuration sanitizer; mock only its API callback."""
import argparse,hashlib,json,struct,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'theseven'))
from test_native import NativeUI,BASE,STUB
from unicorn.arm64_const import UC_ARM64_REG_X0,UC_ARM64_REG_X1

class Config(NativeUI):
    def hook(self,uc,address,size,userdata):
        if address==STUB+0x100:
            self.returned(1);return
        super().hook(uc,address,size,userdata)

def verify(binary):
    assert hashlib.sha256(binary).hexdigest()=='d13d97ba6103262e66fd574600441006010414051ca4c1eadd25cf00718c4dc1'
    api=Config(binary);api.write64(api.ctx+0xe0,api.renderer);api.write64(api.vtable+0x30,STUB+0x100)
    buffer=api.allocate(88);config=bytearray(88)
    slots=[1,2,4,5,9,11,14,16,17,18,19,25,26,48,49,50,51,52,53,54,55]
    for slot in slots:config[slot]=1
    for slot,value in [(56,100),(60,250),(64,121)]:struct.pack_into('<i',config,slot,value)
    api.uc.mem_write(buffer,bytes(config));api.uc.reg_write(UC_ARM64_REG_X0,api.ctx);api.uc.reg_write(UC_ARM64_REG_X1,buffer);api.call(0x33380)
    actual=bytes(api.uc.mem_read(api.ctx+0x68,88));assert actual==config,'Original module rejects a requested setting'
    assert not actual[3] and actual[11],'Excluded farm/retained goal state changed'
    return {'original_arm64_config_api':'pass','accepted_boolean_fields':len(slots),'accepted_numeric_fields':3,'gameplay_tested':False}
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('library',type=Path);args=p.parse_args();print(json.dumps(verify(args.library.read_bytes()),indent=2))

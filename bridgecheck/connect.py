"""Wait for the isolated Gadget listener; cold emulator launches vary in duration."""
import time
import frida

def connect_gadget(timeout=20):
    address='127.0.0.1:27042';manager=frida.get_device_manager()
    deadline=time.monotonic()+timeout;last=None
    while time.monotonic()<deadline:
        try:return manager.add_remote_device(address).attach('Gadget')
        except (frida.TransportError,frida.ServerNotRunningError,frida.ProcessNotFoundError,frida.TimedOutError) as error:
            last=error
            try:manager.remove_remote_device(address)
            except frida.InvalidArgumentError:pass
            time.sleep(.25)
    raise RuntimeError('Gadget did not become ready: '+str(last)) from last

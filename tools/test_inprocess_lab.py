#!/usr/bin/env python3
"""Run only against the Iris laboratory APK, never label this as a game test."""
import argparse,json,subprocess,time,threading
from pathlib import Path
import frida

PACKAGE='com.irisai.inputprobe'
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--adb',required=True);p.add_argument('--serial',default='emulator-5554');p.add_argument('--agent',type=Path,default=Path('inprocess/dist/agent.js'));p.add_argument('--out',type=Path,default=Path('investigation/inprocess-lab-result.json'));a=p.parse_args()
    adb=[a.adb,'-s',a.serial];results=[];session=script=api=None;completed=False;failure=None
    def cmd(*args):return subprocess.check_output(adb+list(args),text=True,timeout=45).strip()
    def events():
        try:s=cmd('exec-out','run-as',PACKAGE,'cat','files/events.jsonl');return [json.loads(x) for x in s.splitlines() if x]
        except subprocess.CalledProcessError:return []
    def clear():cmd('shell','run-as',PACKAGE,'rm','-f','files/events.jsonl')
    def check(name,condition,**details):
        results.append({'name':name,'passed':bool(condition),**details});print(json.dumps(results[-1]),flush=True)
        if not condition:raise AssertionError(name)
    def gesture(epoch,seq=0,duration=120):return {'epoch':epoch,'sequence':seq,'x1':.2,'y1':.75,'x2':.3,'y2':.65,'duration':duration}
    try:
        cmd('shell','am','start','-W','-n',PACKAGE+'/.ProbeActivity');time.sleep(1)
        pid=int(cmd('shell','pidof',PACKAGE).split()[0]);device=frida.get_device(a.serial,timeout=15);session=device.attach(pid);script=session.create_script(a.agent.read_text());script.on('message',lambda m,d:print(json.dumps({'agent':m}),flush=True));script.load();raw_api=script.exports_sync
        class TimedApi:
            def __getattr__(self,name):
                def invoke(*args):
                    cancel=frida.Cancellable();timer=threading.Timer(45,cancel.cancel);timer.start()
                    try:
                        with cancel:return getattr(raw_api,name)(*args)
                    finally:timer.cancel()
                return invoke
        api=TimedApi()
        state=api.locate_activity();check('find_focused_test_activity',state['package']==PACKAGE and state['foreground'],status=state)
        clear();epoch=api.arm(True);response=api.gesture(gesture(epoch));check('direct_dispatch_accepted',response['accepted'],response=response);time.sleep(.5);record=events();actions=[e['action'] for e in record];check('down_move_up_received_in_same_app',len(actions)>=4 and actions[0]==0 and actions[-1]==1 and 2 in actions,events=record)
        clear();epoch=api.arm(True);api.gesture(gesture(epoch,duration=180));off=api.arm(False);time.sleep(.5);record=events();check('off_cancels_pending_sequence',record and record[-1]['action']==3 and not any(e['action']==1 for e in record),events=record)
        clear();new_epoch=api.arm(True);old=api.gesture(gesture(epoch,1));check('old_epoch_rejected_after_reenable',not old['accepted'],response=old);time.sleep(.25);check('no_events_from_stale_command',not events())
        cmd('shell','am','start','-W','-a','android.settings.SETTINGS');time.sleep(.5);epoch=api.arm(True);response=api.gesture(gesture(epoch));check('switching_to_settings_rejects_game_input',not response['accepted'] and response['reason']=='game_not_foreground',response=response);check('no_background_game_events',not events())
        api.arm(False);cmd('shell','am','start','-W','-n',PACKAGE+'/.ProbeActivity');time.sleep(.5);state=api.locate_activity();epoch=api.arm(True);time.sleep(1.1);response=api.gesture(gesture(epoch));check('lost_heartbeat_rejects_input',not response['accepted'],response=response)
        completed=True
    except Exception as exc:
        failure=type(exc).__name__+': '+str(exc)
        raise
    finally:
        if api:
            try:api.shutdown()
            except Exception:pass
        if script:script.unload()
        if session:session.detach()
        report={'scope':'Iris Input Probe on a local Android emulator only','brawl_stars_tested':False,'friendly_matches_played':0,'checks':results,'completed':completed,'failure':failure,'passed':completed and bool(results) and all(x['passed'] for x in results)}
        a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
if __name__=='__main__':main()

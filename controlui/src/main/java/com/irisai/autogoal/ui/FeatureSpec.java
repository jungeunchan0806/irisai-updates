package com.irisai.autogoal.ui;

/** IDs are the verified original BRF configuration byte offsets, not addresses. */
public final class FeatureSpec {
    public final int slot;
    public final String ko, en, detailKo, detailEn;
    public final boolean visual;
    private FeatureSpec(int slot, String ko, String en, String dk, String de, boolean visual) {
        this.slot=slot;this.ko=ko;this.en=en;detailKo=dk;detailEn=de;this.visual=visual;
    }
    public static final FeatureSpec[] ALL = {
        new FeatureSpec(1,"에임봇","Aim","일반·특수 공격 조준","Aim regular and super attacks",false),
        new FeatureSpec(2,"오토닷지","Auto dodge","날아오는 탄환 회피","Dodge incoming projectiles",false),
        new FeatureSpec(4,"자동 발사","Auto fire","공격 범위 안의 적에게 발사","Fire at enemies within range",false),
        new FeatureSpec(5,"길게 눌러 발사","Hold to shoot","공격 버튼을 누르는 동안 발사","Fire while holding the attack button",false),
        new FeatureSpec(9,"엑스레이","X-Ray","원본 모듈의 은신·덤불 대상 조준","Original module's hidden-target auto aim",false),
        new FeatureSpec(14,"AFK 방지","Anti AFK","자리 비움으로 인한 접속 종료 방지","Original idle-disconnect prevention",false),
        new FeatureSpec(16,"셰이드 중앙 조준","Shade center aim","셰이드의 공격 중심 조준","Center Shade's attacks",false),
        new FeatureSpec(17,"나니 모드","Nani mode","나니 이동 중 피프 자동 조종","Control Peep while moving Nani",false),
        new FeatureSpec(48,"빈 핀","Empty pin","원본 모듈의 빈 핀 기능","Original empty-pin behavior",false),
        new FeatureSpec(18,"적 탄약 표시","Enemy ammo","적의 남은 탄약 표시","Show remaining enemy ammo",true),
        new FeatureSpec(19,"화면 흔들림 끄기","Disable camera shake","카메라 흔들림 제거","Remove camera shake",true),
        new FeatureSpec(25,"FPS 표시","Show FPS","Iris UI에 현재 FPS 표시","Show current FPS in the Iris HUD",true),
        new FeatureSpec(26,"FPS 제한 해제","FPS unlock","원본 모듈의 프레임 제한 설정","Original module's frame-rate limit",true),
    };
    public static boolean valid(int slot,int value) {
        for(FeatureSpec f:ALL)if(f.slot==slot)return value==0||value==1;
        if(slot>=49&&slot<=55)return value==0||value==1;
        return (slot==56&&value>=0&&value<=100)||(slot==60&&value>=0&&value<=1000)||(slot==64&&value>=10&&value<=121);
    }
    private FeatureSpec(){throw new AssertionError();}
}

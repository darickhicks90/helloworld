import math, sys
sys.path.insert(0,'.')
from rig import *
FUR=[(92,78,124),(150,138,182),(202,196,224),(234,232,248),(255,255,255)]
SKIN=[(96,82,128),(160,148,196),(208,202,232),(234,232,250),(255,255,255)]
PETAL=[(140,34,84),(214,74,134),(246,134,180),(255,192,218),(255,236,244)]
LEAF=[(22,66,38),(40,106,52),(76,156,68),(126,198,96),(186,234,146)]
STEEL=[(28,32,50),(70,78,106),(122,132,160),(176,186,210),(232,238,250)]
GOLDR=[(110,70,20),(170,120,30),(224,176,56),(248,214,96),(255,244,190)]
ICE=(110,220,255); ICE_D=(30,90,180); PUP=(10,14,40); MAW=(70,14,40); TOOTH=(255,250,240); LIP=(110,50,100)

def blossom(pose):
    """pose: bob, sway, stand ('bless'|'last'), arm phase, blink, hurt, glow, mouth"""
    r=Rig(); b=pose.get('bob',0); sw=pose.get('sway',0); last=pose.get('stand','bless')=='last'
    crouch=3 if last else 0
    oy=b+crouch
    # legs: full, soft, with long-toed feet; thorny vines wound round them
    for side,(hx,kx,fx) in ((-1,(43,40+sw*0.3,38)),(1,(57,60+sw*0.3,62))):
        if last: kx+=side*3; fx+=side*5
        r.cap(hx+sw,70+oy,8.5,kx,84+oy*0.5,7,FUR,1,fur=0.06)
        r.cap(kx,84+oy*0.5,7,fx,96,5.5,FUR,1,fur=0.05)
        r.ell(fx+side*1.5,98,7,3,SKIN,2,edge=True)
    # hips, waist, chest; long fur falling from the head behind the shoulders
    r.ell(50+sw,64+oy,16.5,11,FUR,3,fur=0.06,furn=18)
    r.ell(50+sw*0.8,50+oy,15.5,17,FUR,4,fur=0.05,furn=16)
    r.ell(50+sw*0.6,30+oy,17,20,FUR,2,fur=0.16,furn=18)   # the mane behind
    # head
    hx0=50+sw*0.5; hy0=21+oy
    r.ell(hx0,hy0,12.5,13.5,FUR,6,fur=0.12,furn=14)
    r.ell(hx0,hy0+3,9,9.5,SKIN,7)
    # the flower crown: two blooms and leaves
    def bloom(cx,cy,R,layer):
        for k in range(6):
            a=k*1.047+0.3
            r.ell(cx+math.cos(a)*R*0.8,cy+math.sin(a)*R*0.55,R*0.62,R*0.45,PETAL,layer,rot=a)
        r.ell(cx,cy,R*0.42,R*0.36,GOLDR,layer+1)
    r.ell(hx0-8,hy0-11,5,2.2,LEAF,8,rot=-0.5); r.ell(hx0+8,hy0-12,5,2.2,LEAF,8,rot=0.5)
    bloom(hx0+1,hy0-14,7,9); bloom(hx0-7,hy0-9,4.2,11)
    # arms
    def limb(sx,sy,ex,ey,wx,wy,layer):
        r.cap(sx,sy,5.5,ex,ey,4.8,FUR,layer,fur=0.1)
        r.cap(ex,ey,4.8,wx,wy,3.8,FUR,layer,fur=0.08)
        # a thorny vine wound round the forearm
        for t in (0.25,0.6):
            vx=ex+(wx-ex)*t; vy=ey+(wy-ey)*t
            r.ell(vx,vy,4.6,1.6,LEAF,layer+1,rot=math.atan2(wy-ey,wx-ex)+1.2)
    ph=pose.get('arm',0)
    if not last:
        # the blessing arm, raised high, palm out, fingers spread
        sx,sy=63+sw*0.7,37+oy; ex,ey=73,25+oy+ph; wx,wy=75,10+oy+ph*1.5
        limb(sx,sy,ex,ey,wx,wy,10)
        r.ell(wx,wy-1,4.6,5,SKIN,12,edge=True)
        for k,(fx,fy) in enumerate(((-5,-5),(-2.4,-8),(0.6,-8.6),(3.4,-7.6),(5.6,-3.6))):
            r.cap(wx+fx*0.4,wy-1+fy*0.3,1.5,wx+fx,wy-1+fy,1.1,SKIN,12)
            r.poly([(wx+fx-0.9,wy-1+fy),(wx+fx*1.15,wy-1+fy*1.18),(wx+fx+0.9,wy-1+fy)],STEEL,13,flat=4)
        # the dagger arm, at her hip
        sx2,sy2=37+sw*0.7,37+oy; ex2,ey2=30,52+oy; wx2,wy2=35,64+oy
        limb(sx2,sy2,ex2,ey2,wx2,wy2,10)
        hand2=(wx2,wy2); blade_dir=(-0.12,1)
    else:
        # last stand: crouched, both clawed hands forward, dagger raised to strike
        sx,sy=64+sw,38+oy; ex,ey=75,48+oy+ph; wx,wy=70,58+oy+ph
        limb(sx,sy,ex,ey,wx,wy,10)
        r.ell(wx,wy+1,4.4,4,SKIN,12,edge=True)
        for k in(-1,0,1): r.poly([(wx+k*2.6-0.9,wy+3),(wx+k*3.2,wy+8),(wx+k*2.6+0.9,wy+3)],STEEL,13,flat=4)
        sx2,sy2=36+sw,38+oy; ex2,ey2=24,42+oy-ph; wx2,wy2=27,30+oy-ph
        limb(sx2,sy2,ex2,ey2,wx2,wy2,10)
        hand2=(wx2,wy2); blade_dir=(0.25,-1)
    # the dagger: a curved blade, gold guard
    hx2,hy2=hand2; dx,dy=blade_dir; dl=math.hypot(dx,dy); dx/=dl; dy/=dl; px,py=-dy,dx
    r.cap(hx2-dx*3,hy2-dy*3,1.4,hx2+dx*2,hy2+dy*2,1.4,GOLDR,13)
    r.poly([(hx2+dx*2+px*3.5,hy2+dy*2+py*3.5),(hx2+dx*2-px*3.5,hy2+dy*2-py*3.5),(hx2+dx*3.2-px*3.5,hy2+dy*3.2-py*3.5),(hx2+dx*3.2+px*3.5,hy2+dy*3.2+py*3.5)],GOLDR,14,flat=3)
    r.poly([(hx2+dx*3+px*2.8,hy2+dy*3+py*2.8),(hx2+dx*19+px*2.4,hy2+dy*19+py*2.4),(hx2+dx*24,hy2+dy*24),(hx2+dx*15-px*2.2,hy2+dy*15-py*2.2),(hx2+dx*3-px*2.4,hy2+dy*3-py*2.4)],STEEL,14,grad=(0,1))
    r.ell(hx2,hy2,4.2,4,SKIN,15,edge=True)

    blink=pose.get('blink',False); hurt=pose.get('hurt',False); glow=pose.get('glow',0)
    def face(g,sc,ox,oy_):
        small=sc<0.8
        y0=hy0; x0=hx0
        # the great eye, high on the brow: icy, slit, under a hooded lid
        if blink or hurt:
            line(g,sc,ox,oy_,x0-4,y0-3,x0+4,y0-3,FUR[0])
        else:
            fill_ell(g,sc,ox,oy_,x0,y0-3,4.4,2.8,FUR[0],0.6)
            fill_ell(g,sc,ox,oy_,x0,y0-2.8,3.6,2.0,ICE,0.5)
            if not small: fill_ell(g,sc,ox,oy_,x0,y0-2.8,2.0,1.9,ICE_D,0.5)
            fill_ell(g,sc,ox,oy_,x0,y0-2.8,0.6 if not small else 0.4,1.9,PUP,0.4)
            line(g,sc,ox,oy_,x0-4.6,y0-5,x0+4.6,y0-5,FUR[0],w=1 if small else 2)
            if not small: dot(g,*P(g,sc,ox,oy_,x0-1.2,y0-3.4),(255,255,255))
        # four lesser eyes, cold and narrowed, slanting down to the middle
        for (ex,ey) in ((-4,1.4),(4,1.4),(-3,4.6),(3,4.6)):
            X=x0+ex; Y=y0+ey
            if blink: line(g,sc,ox,oy_,X-1.4,Y,X+1.4,Y,SKIN[0]); continue
            fill_ell(g,sc,ox,oy_,X,Y,1.9,1.2,PUP,0.6)
            fill_ell(g,sc,ox,oy_,X,Y+0.2,1.4,0.75,ICE,0.5)
            sgn=1 if ex<0 else -1
            line(g,sc,ox,oy_,X-2*sgn,Y-1.6,X+2*sgn,Y-0.7,SKIN[0])
        # a thin, cruel smile with small fangs
        mo=pose.get('mouth',0.2)
        line(g,sc,ox,oy_,x0-3.6,y0+7.4,x0,y0+8+mo,LIP); line(g,sc,ox,oy_,x0,y0+8+mo,x0+3.6,y0+7.2,LIP)
        if mo>0.4: fill_ell(g,sc,ox,oy_,x0,y0+8.2,2.6,0.4+mo,MAW,0.5)
        dot(g,*P(g,sc,ox,oy_,x0-1.6,y0+8.6+mo*0.6),TOOTH); dot(g,*P(g,sc,ox,oy_,x0+1.6,y0+8.6+mo*0.6),TOOTH)
        if glow:
            # the blessing hand alight
            hx_,hy_=(75,6+b+crouch)
            for k in range(10):
                a=k*0.628
                dot(g,*P(g,sc,ox,oy_,hx_+math.cos(a)*9,hy_+math.sin(a)*9),(255,240,160) if k%2 else (255,200,230))
    r.feat(face)
    return r
POSES={
 0:dict(bob=0,sway=0,arm=0),
 1:dict(bob=1,sway=0,arm=1),
 2:dict(bob=0,sway=1.5,arm=0.5),
 3:dict(bob=0,sway=0,arm=-1,glow=1,mouth=0.6),
 4:dict(bob=0,sway=0,arm=0,blink=True),
 5:dict(bob=1.5,sway=-2,arm=2,hurt=True,mouth=0.8),
 6:dict(stand='last',bob=0,sway=0,arm=0,mouth=0.6),
 7:dict(stand='last',bob=1.5,sway=1,arm=2,mouth=0.9),
 8:dict(stand='last',bob=0,sway=0,arm=-1,mouth=0.6),
 9:dict(stand='last',bob=1.5,sway=-1,arm=1,mouth=0.9),
}
if __name__=='__main__':
    sc=0.56
    sheet=Image.new('RGBA',(72*10,72),(0,0,0,0))
    for f in range(10):
        g=blossom(POSES[f]).render(72,72,ox=-8/sc-3,oy=-16/sc-2,scale=sc)
        sheet.alpha_composite(to_img(g),(f*72,0))
    sheet.save('hall_blossom.png')
    S=1.3; OX=8; OY=-10
    d=blossom(dict(bob=0,sway=0,arm=0,mouth=0.3)).render(int(118*S),int(84*S),ox=OX,oy=OY,scale=S)
    im=to_img(d); im.save('duel_blossom.png')
    # where her raised hand is, as fractions of the picture (for the duel's glow)
    print('hand', (75-OX)*S/im.width, (6-OY)*S/im.height, im.size)
    def prev(im,s,name):
        bg=Image.new('RGBA',im.size,(120,170,90,255)); bg.alpha_composite(im); bg.resize((im.width*s,im.height*s),Image.NEAREST).save(name)
    prev(sheet,2,'p_bhall.png'); prev(im,3,'p_bduel.png')

import math, sys
sys.path.insert(0,'.')
from rig import *
FUR=[(52,28,24),(98,54,38),(146,90,58),(186,130,84),(222,176,120)]
BELLY=[(104,62,44),(160,110,74),(198,150,102),(222,184,130),(240,212,160)]
SKIN=[(54,34,36),(96,62,58),(136,96,82),(170,128,106),(200,164,136)]
BONE=[(84,72,56),(160,140,104),(212,196,154),(236,226,194),(252,248,228)]
STEEL=[(36,40,56),(84,90,114),(140,148,172),(194,202,220),(240,244,255)]
WOOD=[(46,28,18),(90,56,32),(128,84,48),(162,112,64),(196,144,84)]
HORN=[(36,26,28),(74,58,56),(120,102,92),(168,152,138),(214,204,192)]
EYE_W=(255,236,150); EYE_O=(255,140,30); EYE_R=(200,40,30); PUP=(20,8,8); GLOW=(255,220,60); GLOW2=(255,250,200)
MAW=(52,10,16); TOOTH=(250,246,228)

def yeti(pose):
    """pose: dict with keys: bob, armR ('down','up','out'), armL ('down','out','reach'), blink, hurt, mouth (0..1)"""
    r=Rig(); b=pose.get('bob',0); hurt=pose.get('hurt',False)
    hy=b
    # legs: thick, bowed, clawed feet
    for side,(hx,kx,fx) in ((-1,(44,41,39)),(1,(56,59,61))):
        r.cap(hx,72+hy,7.5,kx,86+hy*0.5,6.5,FUR,1,fur=0.08)
        r.cap(kx,86+hy*0.5,6.5,fx,96,5.5,FUR,1,fur=0.06)
        r.ell(fx+side*2,97.5,7.5,3.2,SKIN,2,edge=True)
        for k in(-1,0,1): r.poly([(fx+side*2+k*4-1.4,98.5),(fx+side*2+k*4+side*2.2,101),(fx+side*2+k*4+1.4,98.5)],BONE,3,flat=3)
    # the hulking body
    r.ell(50,60+hy,18,22,FUR,3,fur=0.1,furn=24)
    # long shaggy fur hanging off the chest and flanks
    for k in range(7):
        a=0.35+k*0.4; r.ell(50+math.cos(a)*15*(1 if k%2 else -1),66+hy+math.sin(a)*8,4,6,FUR,4,fur=0.3,furn=6)
    # no neck: the head runs down in two shaggy slopes straight into the arms
    r.poly([(50,18+hy),(60,22+hy),(70,46+hy),(62,54+hy),(38,54+hy),(30,46+hy),(40,22+hy)],FUR,5,edge=False,tex='fur')
    r.cap(47,22+hy,7,32,46+hy,7.5,FUR,5,fur=0.14,edge=False)
    r.cap(53,22+hy,7,68,46+hy,7.5,FUR,5,fur=0.14,edge=False)
    # the head, set into the slope, a shaggy mane
    r.ell(50,28+hy,15,8,FUR,6,fur=0.25,furn=16)
    r.ell(50,36+hy,13,12.5,SKIN,7,fur=0.0)
    r.ell(50,22.5+hy,14,4.5,FUR,8,fur=0.22,furn=14)
    # horns, curling up and out
    for side in(-1,1):
        r.poly([(50+side*9,24+hy),(50+side*19,18+hy),(50+side*23,8+hy),(50+side*19,12+hy),(50+side*14,17+hy),(50+side*7,21+hy)],HORN,9,grad=(0.2,1))
    # arms
    def arm(side,mode):
        sx=50+side*18; sy=47+hy
        if mode=='down': e=(sx+side*5,66+hy); w=(sx+side*6,84+hy)
        elif mode=='out': e=(sx+side*13,52+hy); w=(sx+side*26,47+hy)
        elif mode=='reach': e=(sx+side*9,61+hy); w=(sx+side*12,76+hy)
        else: e=(sx+side*9,30+hy); w=(sx+side*7,12+hy)   # up
        r.cap(sx,sy,7,e[0],e[1],6.2,FUR,10,fur=0.12)
        r.cap(e[0],e[1],6.2,w[0],w[1],5,FUR,10,fur=0.1)
        r.ell(w[0],w[1]+2,5.6,5.2,SKIN,11,edge=True)
        # claws
        dirx=w[0]-e[0]; diry=w[1]-e[1]; dl=math.hypot(dirx,diry); ux=dirx/dl; uy=diry/dl
        for k in(-1,0,1):
            bx=w[0]+ux*5+(-uy)*k*3.2; by=w[1]+2+uy*5+ux*k*3.2
            r.poly([(bx-uy*1.4,by+ux*1.4),(bx+ux*5.5,by+uy*5.5),(bx+uy*1.4,by-ux*1.4)],BONE,12,flat=3)
        return w
    wl=arm(-1,pose.get('armL','down'))
    wr=arm(1,pose.get('armR','down'))
    # the axe in the right hand
    ax,ay=wr[0],wr[1]+2
    mode=pose.get('armR','down')
    if mode=='down': top=(ax+4,ay-40); bot=(ax-2,ay+12)
    elif mode=='up': top=(ax+14,ay-16); bot=(ax-12,ay+22)
    else: top=(ax+10,ay-36); bot=(ax-4,ay+10)
    r.cap(bot[0],bot[1],2.2,top[0],top[1],2.2,WOOD,13,edge=True)
    vx=top[0]-bot[0]; vy=top[1]-bot[1]; vl=math.hypot(vx,vy); ux=vx/vl; uy=vy/vl; px=-uy; py=ux
    hx0=top[0]-ux*3; hy0=top[1]-uy*3
    for side in(-1,1):
        # a heavy, flaring bit with a notch
        pts=[(hx0+px*side*2-ux*6,hy0+py*side*2-uy*6),(hx0+px*side*13-ux*11,hy0+py*side*13-uy*11),(hx0+px*side*16-ux*3,hy0+py*side*16-uy*3),
             (hx0+px*side*13.5,hy0+py*side*13.5),(hx0+px*side*16+ux*3,hy0+py*side*16+uy*3),(hx0+px*side*13+ux*9,hy0+py*side*13+uy*9),(hx0+px*side*2+ux*5,hy0+py*side*2+uy*5)]
        r.poly(pts,STEEL,14,grad=(0,1))
    r.cap(top[0]-ux*7,top[1]-uy*7,3.2,top[0]+ux*1,top[1]+uy*1,3.2,STEEL,15,edge=True)
    # the hand closes over the haft
    r.ell(ax,ay,5.6,5.2,SKIN,16,edge=True)

    blink=pose.get('blink',False); mouth=pose.get('mouth',0.3)
    def face(g,sc,ox,oy):
        y0=hy
        small=sc<0.8
        # brow: a heavy scowl
        for side in(-1,1):
            line(g,sc,ox,oy,50+side*2,30.5+y0,50+side*12,27+y0,SKIN[0],w=2 if not small else 1)
        # the great eye on the brow
        if blink or hurt:
            line(g,sc,ox,oy,44,26+y0,56,26+y0,SKIN[0],w=1 if small else 2)
        else:
            fill_ell(g,sc,ox,oy,50,26+y0,6.6,4.2,SKIN[0],0.6)
            fill_ell(g,sc,ox,oy,50,26+y0,5.6,3.3,EYE_W,0.5)
            fill_ell(g,sc,ox,oy,50,26+y0,3.3,3.1,EYE_O,0.5)
            if not small: fill_ell(g,sc,ox,oy,50,26+y0,2.2,2.2,EYE_R,0.5)
            fill_ell(g,sc,ox,oy,50,26+y0,0.9 if not small else 0.4,3.0,PUP,0.4)
            if not small:
                # the heavy lid, scowling down at the corners
                line(g,sc,ox,oy,43.5,22.8+y0,50,24.2+y0,SKIN[0],w=2); line(g,sc,ox,oy,50,24.2+y0,56.5,22.8+y0,SKIN[0],w=2)
                dot(g,*P(g,sc,ox,oy,48.2,25+y0),(255,255,255))
        # four lesser eyes, glowing, slanted in anger
        for (ex,ey) in((42.5,32),(57.5,32),(44.5,36.5),(55.5,36.5)):
            if blink: line(g,sc,ox,oy,ex-1.8,ey+y0,ex+1.8,ey+y0,SKIN[0]); continue
            fill_ell(g,sc,ox,oy,ex,ey+y0,2.4,1.5,SKIN[0],0.6)
            fill_ell(g,sc,ox,oy,ex,ey+y0+0.3,1.7,1.0,GLOW,0.5)
            if not small: fill_ell(g,sc,ox,oy,ex,ey+y0+0.3,0.6,0.9,EYE_R,0.4)
            sgn=1 if ex<50 else -1
            line(g,sc,ox,oy,ex-2.4*sgn,ey-1.8+y0,ex+2.4*sgn,ey-0.6+y0,SKIN[0])
        # the maw: a jagged grimace with tusks
        mo=mouth
        fill_ell(g,sc,ox,oy,50,42.5+y0,9,1.6+mo*3.0,MAW,0.6)
        n=7
        for i in range(n):
            tx=42.5+i*15/(n-1)
            line(g,sc,ox,oy,tx,41.2+y0-mo*1.6,tx,42.6+y0-mo*1.0,TOOTH)
        for side in(-1,1):
            line(g,sc,ox,oy,50+side*8,45+y0+mo*2,50+side*8.5,39.5+y0,TOOTH,w=1 if small else 2)
    r.feat(face)
    return r
POSES={
 0:dict(bob=0,armL='down',armR='down',mouth=0.3),
 1:dict(bob=1.5,armL='down',armR='down',mouth=0.2),
 2:dict(bob=0,armL='reach',armR='up',mouth=0.8),
 3:dict(bob=-1,armL='out',armR='up',mouth=1.0),
 4:dict(bob=0,armL='down',armR='down',blink=True,mouth=0.3),
 5:dict(bob=2,armL='out',armR='down',hurt=True,mouth=0.9),
}
if __name__=='__main__':
    # the hall sheet: 6 cells of 72, figure 56 tall standing on the cell's floor
    sc=0.62
    sheet=Image.new('RGBA',(72*6,72),(0,0,0,0))
    for f in range(6):
        g=yeti(POSES[f]).render(72,72,ox=-8,oy=-13.5,scale=sc)
        sheet.alpha_composite(to_img(g),(f*72,0))
    sheet.save('hall_yeti_axe.png')
    # the duel: menacing, axe raised
    d=yeti(dict(bob=0,armL='reach',armR='up',mouth=0.7)).render(172,150,ox=-8,oy=-24,scale=1.18)
    to_img(d).save('duel_stance.png')
    # previews
    def prev(im,s,name):
        bg=Image.new('RGBA',im.size,(120,170,90,255)); bg.alpha_composite(im); bg.resize((im.width*s,im.height*s),Image.NEAREST).save(name)
    prev(sheet,3,'p_yhall.png'); prev(to_img(d),3,'p_yduel.png')
    print('ok')

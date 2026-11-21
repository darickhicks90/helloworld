import math, sys
sys.path.insert(0,'.'); sys.path.insert(0,'../boss3')
from pack import C, save, sheet, ramp, INK, BONE, GOLD
IMP=ramp('46183A','802860','B44678','E68CAA','FFD0D8'); WING=ramp('30142A','54243E','783858','A85A78','D088A0')
BELLY=ramp('8C3C46','C46468','EC968C','FFC2B0','FFE0D0'); HORN=BONE
SH=ramp('1E1E40','2A2C5C','383A6E','6E76BE','B4BCF0'); MANE=ramp('143C50','1E6E82','3CAAB4','8CE8E8','D0FFFF')
BR=ramp('5A0C1C','9A1A2A','D03A3A','F07858','FFC090'); BB=ramp('8C3C46','C46468','EC968C','FFC2B0','FFE0D0')
Y=(255,236,110); RED=(255,60,60); W=(255,255,255); M=(60,10,24)
TEAL=ramp('14605A','22A08A','5CD8B0','A8F0D0','C8FFE0')

def imp(view,f,rp=IMP):
    c=C(24,24); hop=[0,-1,-1][f]; st=[0,1,-1][f]; y0=2+hop
    if view!='side':
        # wings behind
        c.poly([(5,9+y0),(0,4+y0),(1,12+y0),(6,14+y0)],WING,0); c.poly([(19,9+y0),(24,4+y0),(23,12+y0),(18,14+y0)],WING,0)
        c.blob(8.5+st,21.2,3,1.8,rp,1); c.blob(15.5-st,21.2,3,1.8,rp,1)
        c.blob(12,12+y0,8.2,7.6,rp,2,fur=0.05)
        if view=='front': c.blob(12,16+y0,4.4,2.4,BELLY,3)
        c.poly([(6,8+y0),(4,1+y0),(9,6+y0)],HORN,4); c.poly([(18,8+y0),(20,1+y0),(15,6+y0)],HORN,4)
        if view=='back': c.poly([(11,17+y0),(13,17+y0),(16,23),(13,21)],rp,5)
    else:
        c.poly([(9,9+y0),(3,3+y0),(4,12+y0),(9,14+y0)],WING,0)
        c.blob(10+st,21.2,3,1.8,rp,1); c.blob(14-st,21.2,3,1.8,rp,3)
        c.poly([(5,16+y0),(1,19+y0),(0,16+y0),(4,15+y0)],rp,1)
        c.blob(12.5,12+y0,7.8,7.6,rp,2,fur=0.05)
        c.blob(16,16+y0,3,2.2,BELLY,3)
        c.poly([(13,6+y0),(15,0+y0),(16,6+y0)],HORN,4); c.poly([(9,7+y0),(9,1+y0),(12,6+y0)],HORN,1)
    c.inner(); g=c.g
    def px(x,y,col): c.put(x,y,col)
    if view=='front':
        for ex in (8,14):
            for dx in (0,1):
                for dy in (0,1): px(ex+dx,10+y0+dy,Y)
            px(ex+(1 if ex==8 else 0),11+y0,RED)
        for k in range(3): px(7+k,8+y0+k//2,INK); px(16-k,8+y0+k//2,INK)
        for x in range(8,16): px(x,14+y0,M)
        for x in range(9,15): px(x,15+y0,M)
        for x in (8,11,15): px(x,14+y0,W)
        px(10,15+y0,W); px(13,15+y0,W)
    elif view=='side':
        px(16,10+y0,Y); px(17,10+y0,Y); px(16,11+y0,Y); px(17,11+y0,RED)
        px(15,9+y0,INK); px(16,9+y0,INK); px(17,8+y0,INK)
        for x in range(16,21): px(x,14+y0,M)
        px(17,14+y0,W); px(19,14+y0,W); px(18,15+y0,W)
    c.outline(); return c.img()

def stalker(view,f,rp=SH):
    c=C(24,24); st=[0,1,-1][f]
    if view=='side':
        for i,lx in enumerate((6,9,15,18)):
            o=(st if i%2 else -st); c.blob(lx+o,19,1.6,3.6,rp,1 if i in(0,2) else 3)
        c.poly([(4,12),(0,6),(1,5),(5,10)],rp,0); c.blob(0.8,5,1.6,1.6,MANE,0)
        c.blob(11,13,8,4.6,rp,2)
        for k in range(4): c.poly([(7+k*3,10),(8+k*3,5+(k%2)),(10+k*3,10)],MANE,2)
        c.blob(19,11,4.6,4.2,rp,4); c.poly([(16.5,8),(16,3),(19,7)],MANE,5); c.poly([(20,7.5),(22,3),(22,8)],MANE,5)
        c.inner()
        c.put(19,10,Y); c.put(20,10,W); c.put(19,11,RED); c.put(21,10,Y)
        c.put(18,9,INK); c.put(19,9,INK); c.put(21,9,INK)
        for x in (20,21,22): c.put(x,13,M)
        c.put(20,13,W); c.put(22,13,W); c.put(21,14,W)
    else:
        c.blob(7+st,19,2,3.6,rp,1); c.blob(17-st,19,2,3.6,rp,1)
        c.blob(12,14,7.5,5.5,rp,2)
        if view=='back':
            c.poly([(11,12),(13,12),(16,3),(14,2)],rp,3); c.blob(15,2.4,1.8,1.8,MANE,3)
            for k in range(3): c.poly([(8+k*3,11),(9.5+k*3,6),(11+k*3,11)],MANE,4)
        else:
            for k in range(5): c.poly([(4+k*3.6,10),(5.8+k*3.6,4+(k%2)*1.5),(7.6+k*3.6,10)],MANE,2)
            c.blob(12,12,6.2,5.6,rp,4)
            c.poly([(7,9),(6,3),(9.5,7)],MANE,5); c.poly([(17,9),(18,3),(14.5,7)],MANE,5)
        c.inner()
        if view=='front':
            for ex in (9,14):
                c.put(ex,11,Y); c.put(ex+1,11,Y); c.put(ex+(1 if ex==9 else 0),12,RED)
            for k in range(3): c.put(8+k,10-k//2,INK) if False else c.put(8+k,10+(k//2),INK); 
            c.put(15,10,INK); c.put(16,10,INK); c.put(14,11-1,INK)
            for x in range(10,15): c.put(x,15,M)
            c.put(10,15,W); c.put(12,15,W); c.put(14,15,W); c.put(11,16,W); c.put(13,16,W)
    c.outline(); return c.img()

def behemoth(view,f,rp=BR):
    c=C(32,32); st=[0,1,-1][f]
    if view=='side':
        c.blob(12-st,27,4,4.4,rp,1); c.blob(19+st,27,4,4.4,rp,3)
        c.poly([(5,22),(0,26),(1,21),(4,19)],rp,0)
        c.blob(15,18,10.5,9.5,rp,2,fur=0.04)
        c.blob(22,21,3.2,5,BB,3)
        c.blob(16,20,3.4,6,rp,4)
        c.blob(21,10,6.5,6,rp,5)
        c.poly([(18,6),(20,0),(25,3),(22,4),(21,7)],BONE,6); c.poly([(15,7),(14,1),(18,3),(18,6)],BONE,1)
        c.inner()
        for (x,y,col) in [(23,9,Y),(24,9,Y),(24,10,RED),(22,8,INK),(23,8,INK),(24,8,INK)]: c.put(x,y,col)
        for x in range(22,28): c.put(x,13,M)
        for x in (23,25,27): c.put(x,13,W)
        c.put(26,14,W)
    else:
        c.blob(10,27.5-(st>0),4,4.4,rp,1); c.blob(22,27.5-(st<0),4,4.4,rp,1)
        c.poly([(23,22),(31,24),(30,20),(27,20)],rp,0)
        c.blob(16,18,11.5,10,rp,2,fur=0.04)
        if view=='front': c.blob(16,22,7,5.5,BB,3)
        for s_ in(-1,1): c.blob(16+s_*11.5,19+s_*st,3.6,6,rp,4); c.blob(16+s_*12,25+s_*st,3.6,3,rp,4)
        c.blob(16,11,7.6,6.4,rp,5)
        for s_ in(-1,1): c.poly([(16+s_*5,8),(16+s_*11,5),(16+s_*13,0),(16+s_*10,3),(16+s_*4,5)],BONE,6)
        c.inner()
        if view=='front':
            for ex in (11,18):
                for dx in range(3): c.put(ex+dx,10,Y); c.put(ex+dx,11,Y)
                c.put(ex+1,11,RED)
            for k in range(4): c.put(10+k,7+k//2,INK); c.put(22-k,7+k//2,INK)
            for x in range(11,22): c.put(x,14,M)
            for x in range(12,21): c.put(x,15,M)
            for x in range(12,21,2): c.put(x,14,W)
            for x in range(13,20,2): c.put(x,16,W)
            for x in range(13,20): c.put(x,16,M) if x%2 else None
    c.outline(); return c.img()

def walker(fn,name,**kw):
    from PIL import ImageOps
    rows=[]
    for view in ('front','side','side','back'):
        row=[fn(view,f,**kw) for f in range(3)]
        rows.append(row)
    # rows: down, left, right, up. Side frames are drawn facing right: mirror for left.
    rows[1]=[ImageOps.mirror(im) for im in rows[1]]
    save(sheet(rows,4),'characters',name+'_4dir_f3')
walker(imp,'imp'); walker(imp,'imp_ally',rp=TEAL)
walker(stalker,'stalker'); walker(behemoth,'behemoth')
walker(stalker,'stalker_ally',rp=TEAL); walker(behemoth,'behemoth_ally',rp=TEAL)
# the two bosses, front on, two idle frames each (from the rigs)
from rig import to_img
import yeti, blossom
from PIL import Image
for nm,mod,poses in (('five_eyed',yeti,[yeti.POSES[0],yeti.POSES[1]]),('blossom',blossom,[blossom.POSES[0],blossom.POSES[1]])):
    fr=[]
    for p in poses:
        r=(mod.yeti if nm=='five_eyed' else mod.blossom)(p)
        g=r.render(48,44,ox=6,oy=-6,scale=0.44); fr.append(to_img(g))
    save(sheet([fr]),'characters',nm+'_f2')
print('chars ok')

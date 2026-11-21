import math, random, os, sys
from PIL import Image
sys.path.insert(0,'../boss3')
OUT='out'
INK=(22,16,26)
def ramp(*cs): return [tuple(int(c[i:i+2],16) for i in (0,2,4)) for c in cs]
GRASS=ramp('2E5A24','4E8A34','6CA642','7CB84C','94CC5E'); LEAF=ramp('1E4224','2E6232','4A8A3E','78B456','AEDC80')
BARK=ramp('2E1C10','4A301A','6A4628','8A623A','B08858'); DIRT=ramp('7A5A30','A8844E','CCAA72','E0C490','F0DCB0')
STONE=ramp('2A2C3A','4E5266','767C94','A4AABE','D4D8E6'); POND=ramp('1E3E5A','3A6A8A','5A94B8','8CC0DC','D0ECF8')
MUSH=ramp('6A1018','B02028','E04040','F87878','FFD0C8'); GLOWM=ramp('14406A','2A70B0','4AA8E0','A8E8FF','E8FAFF')
PETAL=ramp('8C2254','D64A86','F686B4','FFC0DA','FFECF4'); AUT=ramp('5A2A10','8A4A1E','C8782A','F0B048','FFE090')
GOLD=ramp('6A4A10','A88420','F0CC50','F8E070','FFF8C0'); BONE=ramp('544838','A08C68','D4C49A','ECE2C2','FCF8E4')
STEEL=ramp('242838','545A72','8C94AC','C2CADC','F0F4FF'); WOOD=ramp('3A2414','5A3A22','7A5232','9A7048','C09868')
L=(-0.5,-0.7,0.5); _n=math.sqrt(sum(c*c for c in L)); L=tuple(c/_n for c in L)
class C:
    """A canvas of RGBA tuples with shaded blobs, polys and an ink outline."""
    def __init__(s,w,h): s.w=w; s.h=h; s.g=[[None]*w for _ in range(h)]; s.layer=[[-1]*w for _ in range(h)]; s.part=[[-1]*w for _ in range(h)]; s.np=0; s.ramps={}
    def put(s,x,y,c,layer=99,part=-1):
        x=int(x); y=int(y)
        if 0<=x<s.w and 0<=y<s.h: s.g[y][x]=c; s.layer[y][x]=layer; s.part[y][x]=part
    def blob(s,cx,cy,rx,ry,rp,layer=0,fur=0.0,furn=12,flat=None):
        pid=s.np; s.np+=1; s.ramps[pid]=rp
        for y in range(s.h):
            for x in range(s.w):
                dx=x+0.5-cx; dy=y+0.5-cy; a=math.atan2(dy,dx)
                k=1+fur*(1-abs(((a*furn/6.2832+cx*0.1)%1)-0.5)*2)
                nx=dx/(rx*k); ny=dy/(ry*k)
                if nx*nx+ny*ny>1 or s.layer[y][x]>layer: continue
                ex=dx/rx; ey=dy/ry; d=min(1,ex*ex+ey*ey); nz=math.sqrt(1-d)
                l=ex*0.85*L[0]+ey*0.85*L[1]+nz*L[2]
                i=flat if flat is not None else (0 if l<-0.15 else 1 if l<0.28 else 2 if l<0.68 else 3 if l<0.9 else 4)
                s.put(x,y,rp[i],layer,pid)
    def poly(s,pts,rp,layer=0,tone=None):
        pid=s.np; s.np+=1; s.ramps[pid]=rp
        ys=[p[1] for p in pts]; y0=min(ys); y1=max(ys)
        for y in range(s.h):
            for x in range(s.w):
                X=x+0.5; Y=y+0.5; c=False; j=len(pts)-1
                for i in range(len(pts)):
                    xi,yi=pts[i]; xj,yj=pts[j]
                    if (yi>Y)!=(yj>Y) and X<(xj-xi)*(Y-yi)/(yj-yi)+xi: c=not c
                    j=i
                if c and s.layer[y][x]<=layer:
                    t=(Y-y0)/max(1e-6,y1-y0)
                    s.put(x,y,rp[tone if tone is not None else (3 if t<0.3 else 2 if t<0.7 else 1)],layer,pid)
    def rect(s,l,t,r,b,c,layer=0):
        for y in range(int(t),int(b)):
            for x in range(int(l),int(r)): s.put(x,y,c,layer)
    def inner(s):
        q=[r[:] for r in s.g]
        for y in range(s.h):
            for x in range(s.w):
                p=s.part[y][x]
                if p<0: continue
                for dx,dy in((1,0),(-1,0),(0,1),(0,-1)):
                    xx=x+dx; yy=y+dy
                    if 0<=xx<s.w and 0<=yy<s.h and s.part[yy][xx]>=0 and s.part[yy][xx]!=p and s.layer[yy][xx]>s.layer[y][x] and s.ramps.get(s.part[yy][xx])!=s.ramps.get(p):
                        q[y][x]=s.ramps[p][0]; break
        s.g=q
    def outline(s,col=INK):
        q=[r[:] for r in s.g]
        for y in range(s.h):
            for x in range(s.w):
                if q[y][x] is None and any(0<=x+dx<s.w and 0<=y+dy<s.h and q[y+dy][x+dx] is not None for dx,dy in((1,0),(-1,0),(0,1),(0,-1))): s.g[y][x]=col
    def img(s):
        im=Image.new('RGBA',(s.w,s.h),(0,0,0,0))
        for y in range(s.h):
            for x in range(s.w):
                c=s.g[y][x]
                if c is not None: im.putpixel((x,y),tuple(c)+((255,) if len(c)==3 else ()))
        return im
def save(im,cat,name):
    os.makedirs(f'{OUT}/{cat}',exist_ok=True); im.save(f'{OUT}/{cat}/{name}.png')
def sheet(frames,rows=1):
    w=frames[0][0].width; h=frames[0][0].height
    im=Image.new('RGBA',(w*len(frames[0]),h*rows),(0,0,0,0))
    for r,row in enumerate(frames):
        for i,f in enumerate(row): im.alpha_composite(f,(i*w,r*h))
    return im
R=random.Random(7)

# =================== tiles (16 x 16), seamless
def tile(base_rp,seed,extra=None):
    rnd=random.Random(seed); c=C(16,16)
    for y in range(16):
        for x in range(16): c.put(x,y,base_rp[2])
    for k in range(10):
        x=rnd.randrange(16); y=rnd.randrange(16); c.put(x,y,base_rp[1]); c.put((x+1)%16,y,base_rp[1])
    for k in range(8):
        x=rnd.randrange(16); y=rnd.randrange(16); c.put(x,y,base_rp[3])
    if extra: extra(c,rnd)
    return c.img()
def tufts(c,rnd,n=5):
    for k in range(n):
        x=rnd.randrange(1,15); y=rnd.randrange(2,16); c.put(x-1,y,GRASS[0]); c.put(x+1,y,GRASS[0]); c.put(x,y-1,GRASS[4]); c.put(x,y,GRASS[1])
save(tile(GRASS,1,tufts),'tiles','grass')
save(tile(GRASS,2,lambda c,r:(tufts(c,r,3),[c.put(r.randrange(16),r.randrange(16),col) for col in [(248,240,240),(232,120,160),(248,224,96),(128,144,240)]])),'tiles','grass_flowers')
save(tile(ramp('1E4220','2E5A24','3E7030','4E8A34','6CA642'),3,lambda c,r:tufts(c,r,4)),'tiles','grass_dark')
save(tile(DIRT,4),'tiles','dirt_path')
save(tile(DIRT,5,lambda c,r:[(c.put(x,y,STONE[3]),c.put(x+1,y,STONE[2]),c.put(x,y+1,STONE[1])) for x,y in [(r.randrange(14),r.randrange(14)) for _ in range(4)]]),'tiles','dirt_pebbles')
save(tile(ramp('3A2A18','5A4228','6E5434','8A6A44','A88458'),6,lambda c,r:[c.put(r.randrange(16),r.randrange(16),col) for col in [AUT[2],AUT[3],AUT[1],(200,80,40),AUT[2],LEAF[3]]*2]),'tiles','forest_floor')
def mossy(c,r):
    for y in range(16):
        for x in range(16): c.put(x,y,STONE[2] if (x//8+y//8)%2==0 else STONE[3])
    for y in (0,8): 
        for x in range(16): c.put(x,y,STONE[1])
    for x in (0,8):
        for y in range(16): c.put(x if y<8 else (x+4)%16,y,STONE[1])
    for k in range(12): c.put(r.randrange(16),r.randrange(16),LEAF[2+r.randrange(2)])
save(tile(STONE,7,mossy),'tiles','moss_stone')

# =================== walls (16 x 16)
def hedge(seed):
    c=C(16,16); c.rect(0,0,16,16,LEAF[1])
    rnd=random.Random(seed)
    for (x,y,r) in [(3,4,5),(11,3,5),(7,10,6),(14,11,4),(1,12,4)]:
        c.blob(x,y,r,r*0.9,LEAF,1)
    for k in range(5): x=rnd.randrange(16); y=rnd.randrange(16); c.put(x,y,(230,70,70))
    return c.img()
save(hedge(1),'walls','hedge')
def canopy():
    c=C(16,16); c.rect(0,0,16,16,LEAF[0])
    for (x,y,r) in [(2,4,6),(10,3,6),(6,9,6),(15,10,6),(0,13,5)]: c.blob(x,y,r,r,LEAF,1)
    return c.img()
save(canopy(),'walls','tree_canopy')
def water(ph):
    c=C(16,16); c.rect(0,0,16,16,POND[2])
    for y in range(16):
        for x in range(16):
            if (x+y*3+ph*5)%11==0: c.put(x,y,POND[3])
            if (x*2-y+ph*3)%17==0: c.put(x,y,POND[1])
    for x in range(3,8): c.put((x+ph*4)%16,5,POND[4])
    for x in range(9,13): c.put((x+ph*4)%16,12,POND[4])
    return c.img()
save(sheet([[water(0),water(1)]]),'walls','pond_water_f2')
def rockwall():
    c=C(16,16); c.rect(0,0,16,16,STONE[0])
    for (x,y,rx,ry) in [(4,4,5,4),(12,5,5,5),(5,12,6,4),(13,13,4,4)]: c.blob(x,y,rx,ry,STONE,1)
    c.inner()
    for k in range(4): c.put(R.randrange(16),R.randrange(16),LEAF[2])
    return c.img()
save(rockwall(),'walls','rock_wall')
def bramble():
    c=C(16,16)
    for (x,y,r) in [(4,10,5),(11,9,6),(8,4,4)]: c.blob(x,y,r,r*0.8,ramp('1E3A1C','2E5228','466E34','5E8A40','86AA5E'),1,fur=0.35,furn=10)
    c.inner()
    for (x,y) in [(2,6),(13,4),(7,13),(14,14),(1,14),(9,1)]: c.put(x,y,BONE[3]); c.put(x,y+1,BONE[1])
    c.outline(); return c.img()
save(bramble(),'walls','bramble')

# =================== props
def tree(rp,w=32,h=48,fruit=None,seed=0,pine=False):
    c=C(w,h); cx=w/2; bot=h-1
    # trunk and roots
    c.poly([(cx-4,bot-20),(cx+4,bot-20),(cx+5,bot-2),(cx+8,bot),(cx-8,bot),(cx-5,bot-2)],BARK,0)
    if pine:
        for k,(yy,ww) in enumerate([(bot-14,13),(bot-22,11),(bot-30,9),(bot-38,6)]):
            c.poly([(cx-ww,yy),(cx,yy-14),(cx+ww,yy)],rp,1+k)
    else:
        for (dx,dy,r) in [(0,-30,13),(-8,-24,9),(8,-24,9),(-3,-35,9),(5,-33,8)]:
            c.blob(cx+dx,bot+dy,r,r*0.92,rp,1,fur=0.12,furn=14)
    if fruit:
        rnd=random.Random(seed)
        for k in range(5): x=int(cx+rnd.randint(-10,10)); y=int(bot-24-rnd.randint(-6,12)); c.put(x,y,fruit); c.put(x+1,y,fruit); c.put(x,y-1,(255,255,255))
    c.inner(); c.outline(); return c.img()
save(tree(LEAF),'props','oak_tree')
save(tree(AUT,fruit=(200,40,40),seed=3),'props','autumn_tree')
save(tree(PETAL,fruit=(255,255,255),seed=5),'props','blossom_tree')
save(tree(ramp('0E2E22','1A4A34','2A6A44','46905A','7AB878'),24,48,pine=True),'props','pine_tree')
def stump():
    c=C(16,16); c.blob(8,10,6,4,BARK,0); c.rect(2,9,14,15,BARK[2],0); c.blob(8,8,6,2.5,ramp('8A6A40','B08858','D8B078','E8C890','F8E0B0'),2,flat=3)
    c.put(7,8,BARK[2]); c.put(8,8,BARK[2]); c.put(13,12,(230,70,70)); c.put(13,13,(250,250,250))
    for x in range(2,14): c.put(x,14,BARK[0])
    c.outline(); return c.img()
save(stump(),'props','stump')
def log():
    c=C(32,16); c.rect(1,5,28,13,BARK[2]); 
    for x in range(1,28): c.put(x,5,BARK[3]); c.put(x,6,BARK[3]); c.put(x,12,BARK[1])
    for x in range(4,26,6): c.put(x,8,BARK[0]); c.put(x+1,8,BARK[0])
    c.blob(28,9,4,4,ramp('8A6A40','B08858','D8B078','E8C890','F8E0B0'),2,flat=3); c.put(28,9,BARK[2])
    for x in range(4,14): c.put(x,4,LEAF[2]); 
    for x in range(5,11): c.put(x,3,LEAF[3])
    c.outline(); return c.img()
save(log(),'props','fallen_log')
def boulder():
    c=C(24,16); c.blob(11,9,10,6.5,STONE,0); c.blob(17,10,6,5,STONE,1); c.inner()
    for x in range(5,11): c.put(x,4,LEAF[2])
    c.outline(); return c.img()
save(boulder(),'props','boulder')
def shrooms(rp,glow_ph=None):
    c=C(16,16)
    if glow_ph is not None:
        for y in range(16):
            for x in range(16):
                d=math.hypot(x-8,y-9)
                if d<7.5: c.put(x,y,(150,220,255,int((50 if glow_ph==0 else 85)*(1-d/7.5))+10),-5)
    for (x,y,s) in [(5,8,1.0),(11,10,0.75)]:
        c.rect(x-1,y,x+1,15,BONE[3],1); c.blob(x,y,4.5*s,3*s,rp,2)
        c.put(x-2,y-1,(255,255,255)); c.put(x+1,y,(255,255,255))
    c.outline(); return c.img()
save(shrooms(MUSH),'props','mushrooms')
save(sheet([[shrooms(GLOWM,0),shrooms(GLOWM,1)]]),'props','glow_mushrooms_f2')
def rootdoor(sealed=False):
    c=C(32,40)
    c.blob(16,18,15,16,BARK,0); c.rect(1,18,31,40,BARK[2],0)
    for k in range(7): a=math.pi+k*0.52; c.blob(16+math.cos(a)*13,18+math.sin(a)*13,3,3,BARK,1)
    c.blob(16,20,9,10,ramp('060408','0C0A10','0C0A10','16121C','16121C'),2,flat=1); c.rect(7,20,25,40,(12,10,16),2)
    if sealed:
        for y in (22,27,32): 
            for x in range(7,25): c.put(x,y+((x//3)%2),LEAF[1],3)
    else:
        c.rect(14,24,18,28,GOLD[2],3); c.rect(15,28,17,32,GOLD[2],3); c.put(15,25,(20,10,10),4)
    for x in range(5,12): c.put(x,3,LEAF[2],5)
    for x in range(20,28): c.put(x,4,LEAF[3],5)
    c.inner(); c.outline(); return c.img()
save(rootdoor(),'kiosks','root_door'); save(rootdoor(True),'props','root_door_sealed')
def gate():
    c=C(32,16)
    for k in range(6): c.rect(2+k*5,1,6+k*5,15,WOOD[2+(k%2)],0)
    c.rect(1,4,31,6,STEEL[1],1); c.rect(1,11,31,13,STEEL[1],1); c.rect(13,6,19,11,(20,14,20),2); c.rect(15,7,17,10,GOLD[3],3)
    c.outline(); return c.img()
save(gate(),'props','locked_gate')

# =================== decor
def tallgrass(ph):
    c=C(16,16)
    for k,x in enumerate((2,5,8,11,14)):
        lean=(ph if k%2 else -ph)
        for y in range(6,16): c.put(x+(lean if y<10 else 0),y,GRASS[1] if y>12 else GRASS[3])
        c.put(x+lean,5,GRASS[4])
    return c.img()
save(sheet([[tallgrass(0),tallgrass(1)]]),'decor','tall_grass_f2')
def fern():
    c=C(16,16)
    for a in (-1.2,-0.6,0,0.6,1.2):
        for t in range(1,9):
            x=8+math.sin(a)*t; y=15-math.cos(a)*t*1.1
            c.put(x,y,LEAF[2]); c.put(x+math.cos(a)*1.5,y+math.sin(a)*0.5,LEAF[3]); c.put(x-math.cos(a)*1.5,y-math.sin(a)*0.5,LEAF[1])
    return c.img()
save(fern(),'decor','fern')
def wild():
    c=C(16,16)
    for (x,y,col) in [(3,9,(248,240,240)),(8,6,(232,120,160)),(12,10,(248,224,96)),(6,12,(128,144,240)),(11,4,(232,120,160))]:
        for y2 in range(y+1,16): c.put(x,y2,GRASS[1])
        for dx,dy in((0,-1),(1,0),(0,1),(-1,0)): c.put(x+dx,y+dy,col)
        c.put(x,y,(255,220,80))
    return c.img()
save(wild(),'decor','wildflowers')
def lily():
    c=C(16,16)
    for (x,y,r) in [(5,6,3.2),(11,10,3.8)]:
        c.blob(x,y,r,r*0.7,LEAF,0); c.put(x,y,POND[2]); c.put(x+1,y,POND[2])
    c.put(4,4,PETAL[3]); c.put(5,4,(255,255,255)); c.put(4,3,PETAL[2])
    c.outline(ramp('0E2E22','0E2E22','0E2E22','0E2E22','0E2E22')[0]); return c.img()
save(lily(),'decor','lily_pads')
def leaves():
    c=C(16,16); rnd=random.Random(11)
    for k in range(9):
        x=rnd.randrange(1,15); y=rnd.randrange(1,15); col=AUT[rnd.randrange(1,4)]
        c.put(x,y,col); c.put(x+1,y,col); c.put(x,y+1,AUT[0])
    return c.img()
save(leaves(),'decor','autumn_leaves')
def flies(ph):
    c=C(16,16)
    for k,(x,y) in enumerate([(3,4),(11,3),(7,9),(13,12),(2,13)]):
        if (k+ph)%4==3: continue
        c.put(x,y,(255,255,190)); 
        for dx,dy in((1,0),(-1,0),(0,1),(0,-1)): c.put(x+dx,y+dy,(240,240,120,90))
    return c.img()
save(sheet([[flies(i) for i in range(4)]]),'decor','fireflies_f4')

# =================== items (16 x 16)
def key():
    c=C(16,16); c.blob(5,6,3.5,3.5,GOLD,0); c.put(5,6,(0,0,0,0)); c.put(5,5,(0,0,0,0))
    c.rect(7,6,14,8,GOLD[3],0); c.rect(11,8,12,11,GOLD[2],0); c.rect(13,8,14,10,GOLD[2],0)
    c.g[6][5]=None; c.g[5][5]=None
    c.outline(); c.put(4,4,(255,255,255)); return c.img()
save(key(),'items','key')
def herb():
    c=C(16,16)
    for a in (-0.7,0,0.7): c.blob(8+math.sin(a)*4,8-math.cos(a)*3,2.6,4,LEAF,1)
    c.rect(7,11,9,15,GRASS[1],0); c.outline(); return c.img()
save(herb(),'items','herb')
def bloom():
    c=C(16,16)
    for k in range(5): a=k*1.2566-1.57; c.blob(8+math.cos(a)*3.6,8+math.sin(a)*3.6,2.8,2.8,ramp('3A6AB0','5A8ADA','8AB8F0','C8E0FF','FFFFFF'),1)
    c.blob(8,8,2,2,GOLD,2); c.outline(); return c.img()
save(bloom(),'items','spirit_bloom')
def heartfruit():
    c=C(16,16); c.blob(6,8,3.5,3.5,MUSH,0); c.blob(10,8,3.5,3.5,MUSH,0); c.poly([(2.6,9),(13.4,9),(8,14.5)],MUSH,0,tone=1)
    c.rect(8,2,9,5,BARK[2],1); c.blob(10.5,3,2,1.2,LEAF,1); c.outline(); return c.img()
save(heartfruit(),'items','heart_fruit')

# =================== kiosks
def shrine():
    c=C(24,32)
    c.blob(12,26,10,4,STONE,0); c.poly([(5,26),(7,6),(17,6),(19,26)],STONE,1)
    c.blob(12,6,6,2.5,STONE,2)
    for (x,y) in [(12,12),(10,16),(14,16),(12,20)]: c.put(x,y,(110,220,255),3); c.put(x,y+1,(40,120,200),3)
    for x in range(6,12): c.put(x,24,LEAF[2],4)
    c.inner(); c.outline(); return c.img()
save(shrine(),'kiosks','rune_shrine')
def chest():
    c=C(24,16)
    c.rect(2,6,22,15,WOOD[2],0); c.blob(12,6,10,4,WOOD,1); c.rect(2,6,22,15,WOOD[2],0)
    for x in range(2,22): c.put(x,7,STEEL[1]); c.put(x,14,WOOD[0])
    for y in range(3,15): c.put(5,y,STEEL[2]); c.put(18,y,STEEL[2])
    c.rect(10,7,14,11,GOLD[2],2); c.put(12,9,(20,10,10),3)
    c.outline(); return c.img()
save(chest(),'kiosks','treasure_chest')
print('environment ok')

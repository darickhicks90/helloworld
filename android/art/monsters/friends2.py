# The companions, round two: more detail, more character, each its own size.
import sys, json, math
src=open('mon.py').read().split("EYEB='wxyz'")[0]
ns={}; exec(src.replace("from PIL import Image",""),ns)
S=ns['S']
from PIL import Image
FP={'K':(22,18,26),'W':(255,255,255),'Y':(255,220,70),'y':(200,140,30),'N':(40,24,20),
 'a':(26,70,34),'b':(46,110,46),'c':(80,154,58),'d':(150,206,96),
 'e':(70,44,28),'f':(116,76,44),'g':(164,116,70),'h':(208,166,112),
 'i':(120,128,150),'j':(176,186,206),'k':(222,230,244),'l':(255,255,255),
 'm':(58,64,66),'n':(96,104,100),'o':(140,148,138),'p':(188,194,180),
 'q':(40,120,160),'r':(90,190,230),'s':(170,236,255),
 't':(120,24,20),'u':(204,60,28),'v':(244,132,40),'w':(255,214,92),
 'C':(150,50,90),'D':(214,92,140),'E':(246,160,192),'F':(255,224,236),
 'B':(200,40,50),'T':(236,226,196),'G':(70,130,60),'P':(250,240,220),'L':(196,214,96),'M':(110,150,52)}
def grid(s): return [list(r) for r in s.render()]
def put(g,x,y,c):
    if 0<=y<len(g) and 0<=x<len(g[0]): g[y][x]=c
def bramble(f):
    W,H=26,24; s=S(W,H); st=[0,1][f]
    s.blob(9+st,22,2.8,1.8,'efgh',0); s.blob(17-st,22,2.8,1.8,'efgh',0)
    # branching antlers of twig
    s.tri([(8,8),(5,0),(7,0.5),(9,5),(10,1),(11.5,2),(10,8)],'efgh',0)
    s.tri([(17,8),(20,0),(21.5,1),(18,6),(22,3),(22.5,5),(18,9)],'efgh',0)
    s.blob(13,14,10,8.5,'abcd',1)
    for (x,y,r) in [(6,9,4),(13,6.5,5),(20,9,4),(4,15,3.5),(22,15,3.5)]: s.blob(x,y,r,r*0.85,'abcd',2)
    s.blob(15,14.5,5.4,4.6,'efgh',3)            # the bark mask
    g=grid(s)
    for x in (12,13,17,18): put(g,x,13,'Y')
    put(g,13,13,'K'); put(g,18,13,'K'); put(g,12,12,'e'); put(g,13,12,'e'); put(g,17,12,'e'); put(g,18,12,'e')
    for x in range(13,18): put(g,x,16,'N')
    put(g,14,17,'T'); put(g,16,17,'T')        # little thorn teeth
    for (x,y) in [(3,11),(9,4),(16,4),(22,8),(5,18),(21,18),(10,10)]: put(g,x,y,'T')
    for (x,y) in [(7,12),(12,8),(9,19),(19,11),(20,17)]: put(g,x,y,'B'); put(g,x+1,y,'D' if (x+y)%2 else 'B')
    for (x,y) in [(6,7),(14,4),(20,7),(3,14),(23,14)]: put(g,x,y,'d')
    return [''.join(r) for r in g]
def pip(f):
    W,H=26,24; s=S(W,H); up=f==0
    # four moth wings, patterned
    if up:
        s.tri([(9,11),(0,1),(1,8),(3,12)],'CDEF',0); s.tri([(17,11),(26,1),(25,8),(23,12)],'CDEF',0)
        s.tri([(9,13),(1,19),(4,15)],'efgh',0); s.tri([(17,13),(25,19),(22,15)],'efgh',0)
    else:
        s.tri([(9,12),(0,21),(1,15),(4,11)],'CDEF',0); s.tri([(17,12),(26,21),(25,15),(22,11)],'CDEF',0)
        s.tri([(9,14),(2,23),(5,15)],'efgh',0); s.tri([(17,14),(24,23),(21,15)],'efgh',0)
    s.blob(13,13,6.5,7.8,'efgh',1)
    s.blob(13,16.5,4.2,4.4,'hhTP',2)
    # feathery antennae-ears
    s.tri([(8,7),(6,0),(10,5)],'efgh',3); s.tri([(18,7),(20,0),(16,5)],'efgh',3)
    g=grid(s)
    for ex in (9,15):
        for dx in (0,1,2):
            for dy in (0,1,2): put(g,ex+dx,9+dy,'Y')
        put(g,ex+1,10,'K'); put(g,ex+1,11,'K'); put(g,ex,9,'W')
    put(g,13,12,'y'); put(g,13,13,'y'); put(g,12,12,'y')
    for (x,y) in [(11,16),(14,17),(13,19),(15,15),(12,18)]: put(g,x,y,'g')
    for (x,y) in [(11,22),(12,22),(14,22),(15,22)]: put(g,x,y,'y')
    # eye-spots on the wings
    if up:
        for (x,y) in [(3,5),(22,5)]: put(g,x,y,'Y'); put(g,x+1,y,'Y'); put(g,x,y+1,'K'); put(g,x+1,y+1,'Y')
    else:
        for (x,y) in [(3,16),(22,16)]: put(g,x,y,'Y'); put(g,x+1,y,'Y'); put(g,x,y+1,'K'); put(g,x+1,y+1,'Y')
    put(g,6,0,'s'); put(g,20,0,'s')
    return [''.join(r) for r in g]
def tove(f):
    W,H=28,22; s=S(W,H); st=[0,1][f]
    # a great plume of a tail, frost-tipped
    s.blob(5,9,4.5,6.5,'ijkl',0); s.blob(5,3.5,3.4,3,'qrss',0); s.blob(3,6,2,2,'qrss',0)
    for (lx,o) in ((9,st),(12,-st),(17,st),(20,-st)): s.blob(lx+o*0.6,19,1.6,2.8,'ijkl',1 if lx in (9,17) else 3)
    s.blob(15,14,8,4.8,'ijkl',2)
    s.blob(21.5,9,4.8,4.4,'ijkl',4)
    s.tri([(18.5,7),(18,0.5),(21,5)],'ijkl',5); s.tri([(22,5),(25,0),(25,7)],'ijkl',5)
    s.blob(25.5,11,2,1.5,'ijkl',5)
    # an icy ruff of a collar
    s.tri([(16,10),(18,15),(20,10),(22,14),(23,11)],'qrss',6)
    g=grid(s)
    put(g,21,8,'s'); put(g,22,8,'q'); put(g,21,9,'q'); put(g,22,9,'K'); put(g,23,8,'K')
    put(g,26,11,'K'); put(g,24,12,'K'); put(g,25,12,'K'); put(g,24,13,'W')
    for (x,y) in [(12,11),(15,10),(10,13),(17,16),(13,15)]: put(g,x,y,'s'); put(g,x,y-1,'r')
    put(g,19,3,'q'); put(g,24,2,'q'); put(g,4,1,'l'); put(g,7,2,'l')
    for x in (9,12,17,20): put(g,x+(st if x in (9,17) else -st),21,'q')
    return [''.join(r) for r in g]
def grumble(f):
    # tall and thin, all hard edges: a standing stone of split slabs, grown over with moss
    W,H=22,34; s=S(W,H); st=[0,1][f]
    def quad(cx,cy,w,h,ramp,layer,lean=0.0):
        s.tri([(cx-w/2+lean,cy-h/2),(cx+w/2+lean,cy-h/2),(cx+w/2,cy+h/2),(cx-w/2,cy+h/2)],ramp,layer)
    # blocky feet and shins
    s.tri([(5.5,30+st*0.3),(10.5,30+st*0.3),(11,33),(5,33)],'mnop',0); s.tri([(11.5,30-st*0.3),(16.5,30-st*0.3),(17,33),(11,33)],'mnop',0)
    quad(8.5,26,3.6,8,'mnop',0,0.4); quad(13.5,26,3.6,8,'mnop',0,-0.4)
    # the body: a tall cut slab, wider at the shoulders, chamfered corners
    s.tri([(5,9),(7,7),(15,7),(17,9),(16,21),(14,24),(8,24),(6,21)],'mnop',1)
    # the head: a faceted block with a jutting brow
    s.tri([(7,4),(9,1),(13,1),(15,4),(14.5,8),(7.5,8)],'mnop',2)
    s.tri([(6.5,4.5),(15.5,4.5),(14.5,6),(7.5,6)],'mnop',3)
    # long arms: stacked angular stones, the fist a heavy wedge
    for i,(y,h) in enumerate([(10,3.6),(14,3.4),(18,3.4)]):
        quad(3.2,y,3.4,h-0.6,'mnop',3+2*(i%2),0.5 if i%2 else -0.5); quad(18.8,y,3.4,h-0.6,'mnop',3+2*(i%2),-0.5 if i%2 else 0.5)
    fx=st*0.5
    s.tri([(1-fx,20.5),(5.5-fx,20.5),(6-fx,25),(3-fx,26.5),(0.5-fx,24)],'mnop',6)
    s.tri([(16.5+fx,20.5),(21+fx,20.5),(21.5+fx,24),(19+fx,26.5),(16+fx,25)],'mnop',6)
    # moss: a flat cap and hanging strips, cut straight
    s.tri([(8,0.5),(14,0.5),(15,2.5),(7,2.5)],'abcd',7)
    s.tri([(7,2.5),(8.2,2.5),(8,5),(7.2,5)],'abcd',7); s.tri([(14,2.5),(15,2.5),(14.8,4.5),(14.2,4.5)],'abcd',7)
    s.tri([(6,12),(8.5,12),(8,17),(6,16)],'abcd',4); s.tri([(14,17),(16,17),(15.5,21),(14,20)],'abcd',4)
    s.tri([(1.5,8),(5,8),(4.5,9.5),(2,9.5)],'abcd',8); s.tri([(17,8),(20.5,8),(20,9.5),(17.5,9.5)],'abcd',8)
    g=grid(s)
    # deep-set glowing eyes under the brow
    put(g,9,6,'s'); put(g,10,6,'r'); put(g,12,6,'r'); put(g,13,6,'s'); put(g,9,7,'q'); put(g,13,7,'q')
    # a carved rune line down its chest, straight cuts
    for y in range(10,22): put(g,11,y,'r' if y%3 else 'q')
    for (x,y) in [(10,12),(12,12),(10,16),(12,16),(10,20),(12,20)]: put(g,x,y,'r')
    # crisp cracks and lit edges on the slab
    for y in range(9,20): put(g,6 if y<15 else 7,y,'p')
    for (x,y) in [(14,10),(15,11),(14,12),(8,19),(9,20),(13,22)]: put(g,x,y,'m')
    for (x,y) in [(7,4),(14,2),(8,8),(3,8),(19,8)]: put(g,x,y,'d')
    for (x,y) in [(16,4),(5,23)]: put(g,x,y,'E')
    return [''.join(r) for r in g]
def ember(f):
    # a salamander with a crest of live flame
    W,H=30,20; s=S(W,H); st=[0,1][f]
    s.tri([(2,15),(9,12),(10,16)],'tuvw',0)                  # tail
    s.blob(4,13,2.5,2,'tuvw',0)
    for (lx,o) in ((9,st),(13,-st),(18,st),(22,-st)): s.blob(lx+o*0.7,17,1.8,2.2,'tuvw',1 if lx in (9,18) else 3)
    s.blob(15,13,8,4,'tuvw',2)
    s.blob(23.5,10,4.6,3.6,'tuvw',4)
    s.blob(15,15,6,1.8,'vvww',5)                             # pale belly
    # crest of flame along the spine
    fl=[(10,9),(13,8),(16,7.5),(19,7),(22,6.5)]
    for i,(x,y) in enumerate(fl):
        h=3.5+((i+f)%2)*1.5
        s.tri([(x-1.6,y+1.5),(x+(0.6 if f else -0.6),y-h),(x+1.6,y+1.5)],'uvww',6)
    g=grid(s)
    put(g,24,9,'W'); put(g,25,9,'K'); put(g,24,10,'Y'); put(g,25,10,'K')
    for x in range(25,28): put(g,x,12,'t')
    put(g,27,11,'K'); put(g,28,10,'v' if f else 'w')        # a puff of smoke/spark at the nostril
    for (x,y) in [(12,12),(16,11),(19,12),(14,14),(21,13),(9,13)]: put(g,x,y,'t'); put(g,x+1,y,'w')
    put(g,1 if f else 0,13,'w'); put(g,1,14 if f else 12,'v')
    return [''.join(r) for r in g]
def sprout(f):
    # a little deer spirit with blossoming antlers
    W,H=26,28; s=S(W,H); st=[0,1][f]
    for (lx,o) in ((8,st),(10,-st),(16,st),(18,-st)): s.blob(lx+o*0.6,24,1.1,3.4,'efgh',0 if lx in (10,18) else 2)
    s.blob(13,18,7,4.6,'efgh',1)
    s.blob(13,19.5,5,2.4,'hhTP',1)
    s.blob(5.5,16,1.8,1.6,'TTPP',1)                          # tuft of a tail
    s.blob(19,11,3.6,4.2,'efgh',3)                           # neck and head
    s.blob(21.5,9,3.6,2.8,'efgh',4)
    s.tri([(18,8),(16,6),(18.5,6.5)],'efgh',4)              # an ear
    # antlers: twigs that end in blossoms and leaves
    s.tri([(19,6),(17,1),(18,1),(20,5),(21,0.5),(22,1),(21,6)],'efgh',2)
    s.blob(16.5,1.5,1.6,1.4,'CDEF',5); s.blob(22,1,1.6,1.4,'CDEF',5); s.blob(19,3,1.2,1.2,'abcd',5)
    g=grid(s)
    put(g,22,8,'K'); put(g,23,8,'W'); put(g,22,9,'K'); put(g,25,10,'N')
    for (x,y) in [(11,16),(14,15),(9,18),(16,17),(12,19)]: put(g,x,y,'P')   # dapples
    put(g,16,1,'Y'); put(g,22,1,'Y')
    put(g,7+st,27,'d'); put(g,17-st,27,'d')               # it leaves a bit of green where it steps
    return [''.join(r) for r in g]
out={'BRAMBLE':[bramble(0),bramble(1)],'PIP':[pip(0),pip(1)],'TOVE':[tove(0),tove(1)],'GRUMBLE':[grumble(0),grumble(1)],'EMBER':[ember(0),ember(1)],'SPROUT':[sprout(0),sprout(1)]}
json.dump({'rows':out,'pal':FP},open('friends2.json','w'))
S8=8; im=Image.new('RGB',(12*31*S8,34*S8),(120,170,90)); i=0
for n in out:
    for fr in out[n]:
        for y,row in enumerate(fr):
            for x,ch in enumerate(row):
                if ch!='.':
                    for a in range(S8):
                        for b in range(S8): im.putpixel((i*31*S8+x*S8+a,y*S8+b),FP[ch])
        i+=1
im.save(sys.argv[1]); print('ok')

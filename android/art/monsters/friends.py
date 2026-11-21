import sys, json
sys.argv=['x']
src=open('mon.py').read().split("EYEB='wxyz'")[0]
ns={}; exec(src.replace("from PIL import Image",""),ns)
S=ns['S']
from PIL import Image
FP={'K':(22,18,26),'W':(255,255,255),'Y':(255,220,70),'y':(200,140,30),'N':(40,24,20),
 'a':(26,70,34),'b':(46,110,46),'c':(80,154,58),'d':(150,206,96),          # leaf
 'e':(70,44,28),'f':(116,76,44),'g':(164,116,70),'h':(208,166,112),         # bark / owl brown
 'i':(120,128,150),'j':(176,186,206),'k':(222,230,244),'l':(255,255,255),   # frost-fur
 'm':(60,62,72),'n':(102,104,118),'o':(148,150,166),'p':(198,200,214),      # stone
 'q':(40,120,160),'r':(90,190,230),'s':(170,236,255),                       # ice / glow
 'B':(200,40,50),'T':(236,226,196),'G':(70,130,60),'P':(250,240,220)}
def bramble(f):
    s=S(24,22); st=[0,1][f]
    s.blob(8+st,20,2.6,1.6,'efgh',0); s.blob(15-st,20,2.6,1.6,'efgh',0)
    s.blob(11,12,9,8,'abcd',1)                       # the round bush body
    for (x,y,r) in [(5,7,4),(11,4.5,4.5),(17,7,4),(4,13,3.5)]: s.blob(x,y,r,r*0.9,'abcd',2)
    s.blob(17,13,4.6,4.2,'efgh',3)                    # a bark face
    rows=s.render(); g=[list(r) for r in rows]
    def P(x,y,c):
        if 0<=x<24 and 0<=y<22: g[y][x]=c
    P(16,12,'Y'); P(19,12,'Y'); P(16,13,'y'); P(19,13,'y'); P(17,15,'N'); P(18,15,'N')
    for (x,y) in [(3,9),(8,3),(14,4),(20,8),(6,16)]: P(x,y,'T')
    for (x,y) in [(7,10),(12,7),(9,15),(14,9)]: P(x,y,'B')
    return [''.join(r) for r in g]
def pip(f):
    s=S(24,22); up=f==0
    if up: s.tri([(8,10),(0,2),(2,8),(1,13),(7,14)],'efgh',0); s.tri([(16,10),(24,2),(22,8),(23,13),(17,14)],'efgh',0)
    else: s.tri([(8,11),(0,18),(2,14),(1,9),(7,8)],'efgh',0); s.tri([(16,11),(24,18),(22,14),(23,9),(17,8)],'efgh',0)
    s.blob(12,12,6.5,7.5,'efgh',1)
    s.blob(12,15,4,4,'hhTP',2)
    s.tri([(7,5),(8,1),(10,5)],'efgh',3); s.tri([(14,5),(16,1),(17,5)],'efgh',3)
    rows=s.render(); g=[list(r) for r in rows]
    def P(x,y,c):
        if 0<=x<24 and 0<=y<22: g[y][x]=c
    for ex in (9,14):
        for dx in (0,1,2):
            for dy in (0,1,2): P(ex+dx,8+dy,'Y')
        P(ex+1,9,'K'); P(ex+1,10,'K'); P(ex,8,'W')
    P(12,11,'y'); P(12,12,'y'); P(11,11,'y')
    for (x,y) in [(10,15),(13,16),(12,18),(14,14)]: P(x,y,'g')
    P(10,20,'y'); P(11,20,'y'); P(13,20,'y'); P(14,20,'y')
    return [''.join(r) for r in g]
def tove(f):
    s=S(24,22); st=[0,1][f]
    # a great fluffy tail curling up behind
    s.blob(4,9,4,6,'ijkl',0,); s.blob(5,4,3,3,'qrss',0)
    for (lx,o) in ((8,st),(11,-st),(15,st),(18,-st)): s.blob(lx+o*0.5,19,1.6,2.6,'ijkl',1 if lx in (8,15) else 3)
    s.blob(13,14,7.5,4.6,'ijkl',2)
    s.blob(19,9,4.6,4.2,'ijkl',4)
    s.tri([(16.5,7),(16,1.5),(19,5.5)],'ijkl',5); s.tri([(20,5.5),(22.5,1),(22.5,7)],'ijkl',5)
    s.blob(22.6,11,1.8,1.4,'ijkl',5)
    rows=s.render(); g=[list(r) for r in rows]
    def P(x,y,c):
        if 0<=x<24 and 0<=y<22: g[y][x]=c
    P(19,8,'r'); P(20,8,'q'); P(19,9,'q'); P(20,9,'K'); P(19,8,'s')
    P(23,11,'K'); P(21,12,'K'); P(22,12,'K')
    for (x,y) in [(11,10),(14,9),(9,12)]: P(x,y,'s'); P(x,y-1,'r')
    P(17,4,'q'); P(21,3,'q')
    return [''.join(r) for r in g]
def grumble(f):
    s=S(24,22); st=[0,1][f]
    s.blob(8,19+st*0.3,3.2,2.6,'mnop',0); s.blob(16,19-st*0.3,3.2,2.6,'mnop',0)
    s.blob(12,12,8,7,'mnop',1)
    s.blob(3.5,13+st,3.4,4,'mnop',2); s.blob(20.5,13-st,3.4,4,'mnop',2)   # big fists
    s.blob(12,5.5,5.5,4.2,'mnop',3)
    s.blob(12,2.5,5,1.8,'abcd',4)                     # moss on its head
    rows=s.render(); g=[list(r) for r in rows]
    def P(x,y,c):
        if 0<=x<24 and 0<=y<22: g[y][x]=c
    for ex in (9,14): P(ex,6,'s'); P(ex+1,6,'r'); P(ex,7,'q'); P(ex+1,7,'q')
    for x in range(9,16): P(x,9,'m')
    for (x,y) in [(6,11),(11,15),(16,10),(18,16),(8,17)]: P(x,y,'m'); P(x+1,y,'p')
    for (x,y) in [(5,8),(19,9),(12,13)]: P(x,y,'c')
    return [''.join(r) for r in g]
out={'BRAMBLE':[bramble(0),bramble(1)],'PIP':[pip(0),pip(1)],'TOVE':[tove(0),tove(1)],'GRUMBLE':[grumble(0),grumble(1)]}
json.dump({'rows':out,'pal':FP},open('friends.json','w'))
S8=8; names=list(out); im=Image.new('RGB',(24*S8*2*4+80,22*S8),(120,170,90))
i=0
for n in names:
    for fr in out[n]:
        for y,row in enumerate(fr):
            for x,ch in enumerate(row):
                if ch!='.':
                    for a in range(S8):
                        for b in range(S8): im.putpixel((i*(24*S8+10)+x*S8+a,y*S8+b),FP[ch])
        i+=1
im.save('friends.png'); print('ok')

# The drake: a behemoth-class beast that drops out of the sky. 40x34, four frames
# (walk, walk, rearing/flying, dazed), in the shared monster ramps so ally/ice/burn recolours work.
import sys
src=open('mon.py').read().split("EYEB='wxyz'")[0]
ns={}; exec(src.replace("from PIL import Image",""),ns); S=ns['S']
BODY='ajeE'; WING='MRRr'; HORN='bcdD'; BELLY='bcdD'
def drake(frame):
    W,H=40,34; s=S(W,H)
    walk=frame in (0,1); rear=frame==2; daze=frame==3
    st=[1,-1,0,0][frame]; sy=3 if daze else 0
    # wings: spread high when rearing, folded half-open otherwise
    if rear:
        s.tri([(17,14),(2,0),(5,7),(1,9),(6,13),(3,17),(12,18)],WING,0)
        s.tri([(23,14),(38,0),(35,7),(39,9),(34,13),(37,17),(28,18)],WING,0)
    elif daze:
        s.tri([(14,18+sy),(3,22+sy),(6,27+sy),(14,25+sy)],WING,0); s.tri([(26,18+sy),(37,22+sy),(34,27+sy),(26,25+sy)],WING,0)
    else:
        s.tri([(15,13),(4,4),(6,10),(2,13),(8,17),(14,19)],WING,0); s.tri([(25,13),(36,4),(34,10),(38,13),(32,17),(26,19)],WING,0)
    # tail curling round behind
    s.tri([(24,25+sy),(34,28+sy),(39,24+sy),(37,29+sy),(26,30+sy)],BODY,1)
    s.tri([(37,22+sy),(40,20+sy),(39,26+sy)],HORN,1)
    # legs
    s.blob(13,28.5+sy-(st>0),3.6,4.2,BODY,2); s.blob(27,28.5+sy-(st<0),3.6,4.2,BODY,2)
    for x in (10,13,16): s.px(x,32,'D')
    for x in (24,27,30): s.px(x,32,'D')
    # body
    s.blob(20,21+sy,10,8,BODY,3)
    s.blob(20,24+sy,6,4.5,BELLY,4)
    # long neck and the head
    hy=6 if rear else (16+sy if daze else 9)
    s.tri([(16,18+sy),(17,hy+3),(23,hy+3),(24,18+sy)],BODY,5)
    s.blob(20,hy,6,4.4,BODY,6)
    s.blob(20,hy+4,4,2.4,BODY,6)             # the snout
    for sd in (-1,1):
        s.tri([(20+sd*3,hy-2),(20+sd*8,hy-8),(20+sd*6,hy-2)],HORN,7)
    g=[list(r) for r in s.render()]
    def P(x,y,c):
        if 0<=x<W and 0<=y<H: g[y][x]=c
    if daze:
        for ex in (16,22): P(ex,hy,'K'); P(ex+1,hy+1,'K'); P(ex+2,hy,'K')
    else:
        for ex in (16,22):
            P(ex,hy,'Y'); P(ex+1,hy,'Y'); P(ex,hy+1,'X'); P(ex+1,hy+1,'Y')
        for k in range(3): P(15+k,hy-2+k//2,'K'); P(25-k,hy-2+k//2,'K')
    # nostrils smouldering, a jagged jaw
    P(18,hy+5,'K'); P(22,hy+5,'K'); P(19,hy+6,'D'); P(21,hy+6,'D')
    if rear or frame==1: P(20,hy+7,'I'); P(19,hy+8,'Y')
    # spines down the back, belly plates
    for y in range(hy+4,20,3): P(17,y,'D'); P(23,y,'D')
    for x in range(16,25,2): P(x,24+sy,'b')
    # wing bones
    if not daze:
        bones=[(17,14,2,0),(17,14,1,9),(17,14,3,17),(23,14,38,0),(23,14,39,9),(23,14,37,17)] if rear else [(15,13,4,4),(15,13,2,13),(25,13,36,4),(25,13,38,13)]
        for (x0,y0,x1,y1) in bones:
            for t in range(11): P(round(x0+(x1-x0)*t/10),round(y0+(y1-y0)*t/10),'r')
    return [''.join(r) for r in g]
rows=[drake(i) for i in range(4)]
if __name__=='__main__':
    import json
    json.dump(rows,open('dragon.json','w'))
    from PIL import Image
    PAL={'K':(28,20,40),'W':(255,255,255),'Y':(255,236,110),'X':(255,70,70),'I':(255,140,70),'M':(60,10,24),
     'a':(30,30,64),'j':(42,44,92),'e':(56,58,110),'E':(110,118,190),'R':(90,12,28),'r':(176,34,46),'h':(236,150,140),'H':(240,120,96),
     'b':(150,120,80),'c':(210,184,136),'d':(240,226,190),'D':(255,250,232)}
    S8=8; im=Image.new('RGB',(4*42*S8,34*S8),(120,170,90))
    for i,f in enumerate(rows):
        for y,row in enumerate(f):
            for x,ch in enumerate(row):
                if ch!='.':
                    for a in range(S8):
                        for b in range(S8): im.putpixel((i*42*S8+x*S8+a,y*S8+b),PAL[ch])
    im.save(sys.argv[1]); print('ok')

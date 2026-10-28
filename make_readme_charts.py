import os, math, numpy as np, matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, Rectangle, FancyArrowPatch, Circle
from matplotlib import font_manager
from pathlib import Path
P=Path(__file__).resolve().parent / 'assets'
P.mkdir(parents=True, exist_ok=True)
plt.rcParams.update({'font.family':'NanumBarunGothic','axes.unicode_minus':False,'figure.dpi':180,'savefig.dpi':190})
BG='#F7F9FC'; DARK='#15233A'; MUTED='#64748B'; BLUE='#275D98'; TEAL='#238B83'; COPPER='#B77949'; GRAY='#DDE5EF'; WHITE='#FFFFFF'; RED='#C76756'

def base(w=12,h=5):
 f,ax=plt.subplots(figsize=(w,h)); f.patch.set_facecolor(BG);ax.set_facecolor(BG); ax.set_xlim(0,100);ax.set_ylim(0,100);ax.set_axis_off();return f,ax

def text(ax,x,y,s,size=12,color=DARK,weight='normal',ha='left',va='center',**kw):
 ax.text(x,y,s,fontsize=size,color=color,fontweight=weight,ha=ha,va=va,**kw)

def pill(ax,x,y,w,h,fc=WHITE,ec='#E5EBF2',r=3):
 b=FancyBboxPatch((x,y),w,h,boxstyle=f'round,pad=0.2,rounding_size={r}',linewidth=0.9,edgecolor=ec,facecolor=fc)
 ax.add_patch(b);return b

def save(fig,name):
 fig.savefig(P/name,facecolor=fig.get_facecolor(),bbox_inches='tight',pad_inches=0.14)
 plt.close(fig)

# 01 evidence board
f,ax=base(13.5,5)
text(ax,4,91,'법인차 운행기록 · 시장 근거 한눈에 보기',22,DARK,'bold')
text(ax,4,82,'공식 통계·언론 보도·사업자 자체 공개 정보를 구분해 제시합니다.',10,MUTED)
items=[
('118만 개','2026년 법인세 신고 대상','국세청 | 차량 보유 법인 수 아님',BLUE),
('308,881대','2024년 1~9월 법인 승용차 등록','카이즈유 인용 보도 | 기간 신규 등록',TEAL),
('39.6%','2017~2021년 운행일지 미작성','국세청 국회자료 인용 | 과거 집계',COPPER),
('10만+ 기업','기존 기업 운행관리 서비스 이용','카택스 | 누적 자체 공개 수치',BLUE)]
for i,(num,l,s,c) in enumerate(items):
 x=4+i*24.1;pill(ax,x,19,22.5,53)
 ax.add_patch(Rectangle((x+1,68.2),20.5,2.2,facecolor=c,edgecolor='none'))
 text(ax,x+2.5,54,num,21,c,'bold')
 text(ax,x+2.5,39,l,10,DARK,'bold')
 # wrapping explicit
 if i==0: line=['국세청 자료 · 법인차','보유량과는 별개']
 elif i==1: line=['기간 내 신규 등록','누적 보유량 아님']
 elif i==2: line=['신고자료 기반 집계','현재 미작성률 아님']
 else: line=['사업자 발표 자료','유료 활성 고객 아님']
 text(ax,x+2.5,29,line[0],8,MUTED)
 text(ax,x+2.5,23,line[1],8,MUTED)
text(ax,4,9,'집계 시점·분모가 서로 달라 각 수치를 직접 비교하거나 합산할 수 없습니다.',9,MUTED)
save(f,'01_market_evidence.png')

# 02 record gap proportion chart
f,ax=base(12.5,5.5)
text(ax,4,91,'법인 업무용승용차 운행기록 작성 현황',21,DARK,'bold')
text(ax,4,82,'2017~2021년 국세청 관련비용 신고자료 집계 / 뉴스1 (2022.10.06)',10,MUTED)
# horizontal stacked bar
x0,x1=6,94; by=51; totalw=x1-x0
written=4198120-1663618
p_unwritten=1663618/4198120
pill(ax,5,24,90,47,fc=WHITE)
ax.add_patch(FancyBboxPatch((x0,by),totalw,11,boxstyle='round,pad=0,rounding_size=3',facecolor=GRAY,edgecolor='none'))
# rectangle inside exact w
ax.add_patch(Rectangle((x0,by),totalw*(1-p_unwritten),11,facecolor=BLUE,edgecolor='none'))
ax.add_patch(FancyBboxPatch((x0+totalw*(1-p_unwritten),by),totalw*p_unwritten,11,boxstyle='round,pad=0,rounding_size=2',facecolor=COPPER,edgecolor='none'))
text(ax,6,70,'집계 합계 4,198,120건',11,MUTED)
text(ax,6,44,f'작성: {written:,}건 · {100*(1-p_unwritten):.1f}%',12,BLUE,'bold')
text(ax,60,44,f'미작성: 1,663,618건 · {100*p_unwritten:.1f}%',12,COPPER,'bold')
text(ax,6,32,'※ 5개 연도 합산 자료로 동일 차량이 연도별 반복 집계될 수 있으며, 2026년 현재 비율이 아닙니다.',9,MUTED)
text(ax,6,13,'SOURCE  뉴스1 · 국세청 국회제출 업무용승용차 관련비용 현황 (2022-10-06)',9,MUTED)
save(f,'02_record_gap.png')

# 03 time-series official
f,ax=base(12.5,5.8)
text(ax,4,92,'1억 원 이상 법인 승용차 신규 등록 추이',21,DARK,'bold')
text(ax,4,83,'2022~2025년 · 국토교통부 신규등록 통계(국세청 2026.05.28 보도자료 인용)',10,MUTED)
vals=[48894,51542,33960,39429]; yrs=['2022','2023','2024','2025']
x_positions=[16,39,62,85]; mx=56000
for y,v,x in zip(yrs,vals,x_positions):
 height=52*v/mx
 ax.add_patch(FancyBboxPatch((x-6,20),12,height,boxstyle='round,pad=0,rounding_size=2',facecolor=(COPPER if y=='2025' else BLUE),edgecolor='none'))
 text(ax,x,20+height+5,f'{v:,}대',12,(COPPER if y=='2025' else DARK),'bold',ha='center')
 text(ax,x,12,y,11,MUTED,'bold',ha='center')
ax.plot([7,94],[19,19],color='#CBD5E1',lw=1)
text(ax,4,4,'※ 고가 승용차(1억 원 이상) 하위 시장의 연간 신규 등록량이며 전체 법인차 보유대수가 아닙니다.',8.4,MUTED)
save(f,'03_premium_registrations.png')

# 04 operating impact simulation
f,ax=base(12.5,5.7)
text(ax,4,92,'기록 수작업 부담: 입력값 기반 운영 시뮬레이션',20,DARK,'bold')
text(ax,4,82,'가정: 차량당 하루 2건 × 월 22일 × 연 12개월 · 기록 검토 1건당 2분',10,MUTED)
vehicles=[5,10,20,30,50]
hrs=[v*2*22*12*2/60 for v in vehicles]
# Use bars
for i,(v,hr) in enumerate(zip(vehicles,hrs)):
 x=13+i*18.1; height=53*hr/930
 ax.add_patch(FancyBboxPatch((x-5,21),10,height,boxstyle='round,pad=0,rounding_size=1.5',facecolor=(TEAL if v==20 else '#8CB5C4'),edgecolor='none'))
 text(ax,x,22+height+4,f'{hr:,.0f}h',12,TEAL if v==20 else DARK,'bold',ha='center')
 text(ax,x,15,f'{v}대',11,MUTED,'bold',ha='center')
text(ax,4,6,'20대 예시 → 연 10,560건 / 수작업 352시간 / 시간당 20,000원 적용 시 704만 원 환산',10,DARK,'bold')
text(ax,4,0,'※ 실제 업무시간 조사나 SHAREMATE 도입 후 절감 결과가 아닙니다. 도입 전후 실측 필요.',8.2,MUTED)
save(f,'04_workload_model.png')

# 05 data flow infographic
f,ax=base(14.4,6.1)
text(ax,3,91,'SHAREMATE · 차량 데이터부터 운행기록부까지',21,DARK,'bold')
text(ax,3,83,'현행 2.2.1에서 확인된 데이터 경로를 단순화한 기술 구성도',10,MUTED)
# row1 blocks
blocks=[(3,53,26,22,'VEHICLE DATA','Pleos Vehicle Odometer\nAAOS Gear P / D / R / N',BLUE),
(37,53,26,22,'RECORD ENGINE','TripLogService\n유효성·체크포인트 검사',TEAL),
(71,53,26,22,'LOCAL STORE','TripLogRepository\nAtomicFile · 로컬 기록',COPPER)]
for x,y,w,h,t,sub,c in blocks:
 pill(ax,x,y,w,h,WHITE)
 ax.add_patch(Rectangle((x,y+h-3),w,3,color=c,ec='none'))
 text(ax,x+2,y+14,t,11,c,'bold')
 for j,line in enumerate(sub.split('\n')):text(ax,x+2,y+8-j*5,line,8.5,MUTED)
for x0,x1 in [(29,37),(63,71)]:
 ax.add_patch(FancyArrowPatch((x0+0.4,64),(x1-0.4,64),arrowstyle='-|>',lw=2.0,mutation_scale=16,color=BLUE))
# second row from storage
ax.add_patch(FancyArrowPatch((84,53),(84,45),arrowstyle='-|>',lw=2.0,mutation_scale=16,color=TEAL))
pill(ax,3,14,43,27,WHITE)
text(ax,5,34,'DRIVER CONFIRMATION',11,BLUE,'bold');text(ax,5,27,'직원 지정 · 운행 목적 · 비용 직접 입력',10,DARK)
text(ax,5,20,'주행 중 상세 입력은 CarUxRestrictions로 제한',8.4,MUTED)
pill(ax,54,14,43,27,WHITE)
text(ax,56,34,'ENTERPRISE OUTPUT',11,TEAL,'bold');text(ax,56,27,'운행기록부 · 직원별 현황 · 경비원장 · 확인사항',9.5,DARK)
text(ax,56,20,'Excel 4개 시트 / 모바일 .smlog 사본',8.4,MUTED)
ax.add_patch(FancyArrowPatch((43,39.5),(70.7,54),arrowstyle='-|>',lw=1.8,mutation_scale=14,color=TEAL,connectionstyle='arc3,rad=-0.08'))
text(ax,3,5,'현재 미구현: 실시간 조직 서버 동기화 · ERP/법인카드 자동연동 · Gleo 실제 음성 호출',9,RED,'bold')
save(f,'05_pleos_architecture.png')

# 06 national tax investigation context, avoid alleged tax loss equivocation
f,ax=base(12.5,4.5)
text(ax,4,89,'실제 세무조사 사례: 법인차의 사적 사용·기록 조작 혐의',19,DARK,'bold')
text(ax,4,79,'국세청 2026.05.28 보도자료 · 조사 착수 발표 기준',10,MUTED)
for i,(n,l,c) in enumerate([('19개','조사 대상 법인',BLUE),('90대','조사 대상 고가차량',TEAL),('약 300억 원','해당 고가차량 가치',COPPER)]):
 x=4+31.6*i;pill(ax,x,31,29,41)
 text(ax,x+2.2,56,n,19,c,'bold')
 text(ax,x+2.2,43,l,10,DARK)
text(ax,4,20,'전체 탈루혐의액 약 3,000억 원은 차량 운행일지 관련 피해액이나 확정 추징액이 아닙니다.',9,RED,'bold')
text(ax,4,10,'조사 대상 사례이므로 실제 위법 판단 및 최종 과세 결과와 구별해야 합니다.',9,MUTED)
save(f,'06_tax_audit_context.png')
print('Generated:')
for p in P.glob('*.png'):print(p.name,p.stat().st_size)

#include <stdio.h>
#include <string.h>
typedef unsigned int u32; typedef unsigned short u16;
#define GENMASK(h,l) (((~0UL) - (1UL << (l)) + 1) & (~0UL >> (63 - (h))))
#define ARRAY_SIZE(a) (sizeof(a)/sizeof((a)[0]))
#define OSM_TABLE_SIZE 40
#define OSM_REG_SIZE 32
#define FREQ_REG 0x110
#define VOLT_REG 0x114
#define XO_RATE 19200000UL
#define OSM_INIT_RATE 300000000UL
#define mb() do{}while(0)
static char lastlog[512];
#define pr_info(...) snprintf(lastlog,sizeof lastlog,__VA_ARGS__)
struct clk_osm { unsigned int (*r)[2]; int locked; };
static u32 clk_osm_read_reg(struct clk_osm *c, u32 off){ int i=(off-FREQ_REG)/OSM_REG_SIZE; return c->r[i][(off-FREQ_REG)%OSM_REG_SIZE?1:0]; }
static void clk_osm_write_reg(struct clk_osm *c, u32 val, u32 off){ int i=(off-FREQ_REG)/OSM_REG_SIZE; if(!c->locked) c->r[i][(off-FREQ_REG)%OSM_REG_SIZE?1:0]=val; }
#include "s9_lut_data.c"
#include "s9_osm_snip.c"
static int fails;
#define CHECK(x,msg) do{ if(!(x)){ printf("FALHA: %s\n",msg); fails++; } }while(0)
int main(void){
  unsigned int a[40][2], b[40][2]; int i,k;
  struct clk_osm c={0};
  /* T1: A75, LUT de fabrica */
  memcpy(a,lut_big,sizeof a); c.r=a; c.locked=0;
  s9_osm_rewrite_lut(&c,"A75",s9_uv_perfcl,ARRAY_SIZE(s9_uv_perfcl),146,151); printf("T1 %s",lastlog);
  for(i=0;i<40;i++){ u32 ol=lut_big[i][0]&0xff, omv=lut_big[i][1]&0xfff, nmv=a[i][1]&0xfff, nl=a[i][0]&0xff; u32 khz=19200*ol; int f=0;
    for(k=0;k<(int)ARRAY_SIZE(s9_uv_perfcl);k++) if(s9_uv_perfcl[k].khz==khz&&s9_uv_perfcl[k].old_mv==omv){ f=1; CHECK(nmv==s9_uv_perfcl[k].new_mv,"mV A75"); }
    CHECK(f,"linha A75 sem par");
    CHECK((a[i][1]&~0xfffu)==(lut_big[i][1]&~0xfffu),"corner A75 preservado");
    CHECK((a[i][0]&~0xffu)==(lut_big[i][0]&~0xffu),"bits altos FREQ A75 preservados");
    CHECK(nl==(ol==146?151:ol),"multiplicador A75"); }
  CHECK((a[26][1]&0xfff)==980 && (a[26][0]&0xff)==151 && (a[39][0]&0xff)==151,"topo A75 = 980 mV / 2899,2 MHz");
  for(i=1;i<40;i++) CHECK((a[i][1]&0xfff)>=(a[i-1][1]&0xfff),"tensao A75 monotona");
  /* T2: A55, inclui a linha de 300 MHz (src=0) */
  memcpy(b,lut_lit,sizeof b); c.r=b;
  s9_osm_rewrite_lut(&c,"A55",s9_uv_pwrcl,ARRAY_SIZE(s9_uv_pwrcl),92,96); printf("T2 %s",lastlog);
  CHECK((b[0][1]&0xfff)==580,"A55 300 MHz = 580 mV"); CHECK(b[0][0]==lut_lit[0][0],"A55 linha 0 FREQ intacta");
  CHECK((b[17][1]&0xfff)==780 && (b[17][0]&0xff)==96 && (b[16][0]&0xff)==88,"topo A55 = 780 mV / 1843,2 MHz");
  for(i=1;i<40;i++) CHECK((b[i][1]&0xfff)>=(b[i-1][1]&0xfff),"tensao A55 monotona");
  /* T3: LUT diferente (uma linha com outra tensao) -> nada regravado */
  memcpy(a,lut_big,sizeof a); a[5][1]=(a[5][1]&~0xfffu)|700; memcpy(b,a,sizeof b); c.r=a;
  s9_osm_rewrite_lut(&c,"A75",s9_uv_perfcl,ARRAY_SIZE(s9_uv_perfcl),146,151); printf("T3 %s",lastlog);
  CHECK(memcmp(a,b,sizeof a)==0,"LUT diferente deve ficar intacta");
  /* T4: tabela errada (A55 na LUT do A75) -> nada regravado */
  memcpy(a,lut_big,sizeof a); c.r=a;
  s9_osm_rewrite_lut(&c,"A75x",s9_uv_pwrcl,ARRAY_SIZE(s9_uv_pwrcl),92,96); printf("T4 %s",lastlog);
  CHECK(memcmp(a,lut_big,sizeof a)==0,"tabela trocada deve ficar intacta");
  /* T5: registradores travados -> registra recusa, nao quebra */
  memcpy(a,lut_big,sizeof a); c.r=a; c.locked=1;
  s9_osm_rewrite_lut(&c,"A75",s9_uv_perfcl,ARRAY_SIZE(s9_uv_perfcl),146,151); printf("T5 %s",lastlog);
  CHECK(memcmp(a,lut_big,sizeof a)==0,"travado: intacto"); CHECK(strstr(lastlog,"recusada em 40")!=NULL,"travado: log de recusa");
  /* T6: reaplicar sobre LUT ja regravada -> nada muda */
  memcpy(a,lut_big,sizeof a); c.r=a; c.locked=0;
  s9_osm_rewrite_lut(&c,"A75",s9_uv_perfcl,ARRAY_SIZE(s9_uv_perfcl),146,151); memcpy(b,a,sizeof b);
  s9_osm_rewrite_lut(&c,"A75",s9_uv_perfcl,ARRAY_SIZE(s9_uv_perfcl),146,151); printf("T6 %s",lastlog);
  CHECK(memcmp(a,b,sizeof a)==0,"segunda aplicacao nao altera");
  printf("%s (%d falhas)\n", fails?"TESTES FALHARAM":"TODOS OS TESTES PASSARAM", fails); return fails!=0;
}

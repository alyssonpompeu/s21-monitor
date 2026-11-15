/* Teste unitario do kernel v3: troca de multiplicador na LUT do OSM.
 * Usa a LUT de fabrica medida no aparelho (s9_lut_data.c) e a funcao
 * extraida do fonte ja alterado (s9_osm_snip.c). */
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
#define pr_info(...) do{}while(0)
#define scnprintf snprintf
#define module_param_string(a,b,c,d)
struct clk_osm { unsigned int (*r)[2]; int locked; };
static u32 clk_osm_read_reg(struct clk_osm *c, u32 off){ int i=(off-FREQ_REG)/OSM_REG_SIZE; return c->r[i][(off-FREQ_REG)%OSM_REG_SIZE?1:0]; }
static void clk_osm_write_reg(struct clk_osm *c, u32 val, u32 off){ int i=(off-FREQ_REG)/OSM_REG_SIZE; if(!c->locked) c->r[i][(off-FREQ_REG)%OSM_REG_SIZE?1:0]=val; }
#include "s9_lut_data.c"
#include "s9_osm_snip.c"
static int fails;
#define CHECK(x,msg) do{ if(!(x)){ printf("FALHA: %s\n",msg); fails++; } }while(0)
static int new_lval(const struct s9_oc *t, int n, u32 khz, u32 mv){ int k; for(k=0;k<n;k++) if(t[k].khz==khz&&t[k].mv==mv) return t[k].new_lval; return -1; }
int main(void){
  unsigned int a[40][2], b[40][2]; int i, changed;
  struct clk_osm c={0};
  /* T1: A75 de fabrica -> multiplicadores novos, tensoes intactas */
  memcpy(a,lut_big,sizeof a); c.r=a; strcpy(s9_osm_result,"nao executado");
  s9_osm_rewrite_lut(&c,"A75",s9_oc_perfcl,ARRAY_SIZE(s9_oc_perfcl));
  changed=0;
  for(i=0;i<40;i++){ u32 ol=lut_big[i][0]&0xff, mv=lut_big[i][1]&0xfff; int nl=new_lval(s9_oc_perfcl,ARRAY_SIZE(s9_oc_perfcl),19200*ol,mv);
    CHECK(nl>0,"A75: linha sem par na tabela");
    CHECK((a[i][0]&0xff)==(u32)nl,"A75: multiplicador novo");
    CHECK(a[i][1]==lut_big[i][1],"A75: registrador de tensao intacto");
    CHECK((a[i][0]&~0xffu)==(lut_big[i][0]&~0xffu),"A75: bits altos de FREQ intactos");
    if((u32)nl!=ol) changed++; }
  CHECK((a[39][0]&0xff)==156 && (a[39][1]&0xfff)==1068,"A75: topo 2995,2 MHz a 1068 mV");
  for(i=1;i<40;i++) CHECK((a[i][0]&0xff)>=(a[i-1][0]&0xff),"A75: frequencia monotona");
  { char exp[96]; snprintf(exp,sizeof exp,"A75: frequencia aceita em %d linhas, recusada em 0",changed); CHECK(strstr(s9_osm_result,exp)!=NULL,"A75: resultado registrado"); }
  printf("T1 %s\n",s9_osm_result);
  /* T2: A55 - linha de 300 MHz (taxa inicial) intacta; topo 1996,8 MHz; resultado acumulado */
  memcpy(b,lut_lit,sizeof b); c.r=b;
  s9_osm_rewrite_lut(&c,"A55",s9_oc_pwrcl,ARRAY_SIZE(s9_oc_pwrcl));
  CHECK(b[0][0]==lut_lit[0][0],"A55: linha de 300 MHz intacta");
  CHECK((b[17][0]&0xff)==104 && (b[17][1]&0xfff)==844,"A55: topo 1996,8 MHz a 844 mV");
  for(i=0;i<40;i++) CHECK(b[i][1]==lut_lit[i][1],"A55: tensoes intactas");
  CHECK(strstr(s9_osm_result,"A75: ")&&strstr(s9_osm_result," | A55: "),"resultado acumula A75 | A55");
  printf("T2 %s\n",s9_osm_result);
  /* T3: LUT diferente -> nada regravado */
  memcpy(a,lut_big,sizeof a); a[5][1]=(a[5][1]&~0xfffu)|700; memcpy(b,a,sizeof b); c.r=a; strcpy(s9_osm_result,"nao executado");
  s9_osm_rewrite_lut(&c,"A75",s9_oc_perfcl,ARRAY_SIZE(s9_oc_perfcl));
  CHECK(memcmp(a,b,sizeof a)==0,"LUT diferente fica intacta"); CHECK(strstr(s9_osm_result,"diferente")!=NULL,"LUT diferente registrada");
  printf("T3 %s\n",s9_osm_result);
  /* T4: registradores travados -> recusa registrada, nada muda */
  memcpy(a,lut_big,sizeof a); c.r=a; c.locked=1; strcpy(s9_osm_result,"nao executado");
  s9_osm_rewrite_lut(&c,"A75",s9_oc_perfcl,ARRAY_SIZE(s9_oc_perfcl));
  CHECK(memcmp(a,lut_big,sizeof a)==0,"travado: intacto"); CHECK(strstr(s9_osm_result,"aceita em 0 linhas, recusada em")!=NULL,"travado: recusa registrada");
  printf("T4 %s\n",s9_osm_result); c.locked=0;
  /* T5: aplicar de novo sobre LUT ja alterada -> reconhece e nao mexe */
  memcpy(a,lut_big,sizeof a); c.r=a;
  s9_osm_rewrite_lut(&c,"A75",s9_oc_perfcl,ARRAY_SIZE(s9_oc_perfcl)); memcpy(b,a,sizeof b);
  strcpy(s9_osm_result,"nao executado");
  s9_osm_rewrite_lut(&c,"A75",s9_oc_perfcl,ARRAY_SIZE(s9_oc_perfcl));
  CHECK(memcmp(a,b,sizeof a)==0,"segunda aplicacao nao altera");
  printf("T5 %s\n",s9_osm_result);
  printf("%s (%d falhas)\n", fails?"TESTES FALHARAM":"TODOS OS TESTES PASSARAM", fails); return fails!=0;
}

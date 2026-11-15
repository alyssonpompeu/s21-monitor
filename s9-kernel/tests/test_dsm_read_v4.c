/* Teste de regressao do kernel v4: leitura de /dev/dsm_ctrl_dev.
 * O maxdsm_read original devolvia o resto do copy_to_user (0 em caso de
 * sucesso), entao "dd if=/dev/dsm_ctrl_dev" sempre lia 0 bytes.
 * Este teste compila a funcao extraida do fonte JA ALTERADO (s9_dsm_snip.c)
 * com stubs e confere: bytes devolvidos, fim de arquivo, leitura curta e erro.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <sys/types.h>

#define __user
typedef long long loff_t_;
#define loff_t loff_t_
struct file { int dummy; };
static struct { int param_size; uint32_t *param; int platform_type; int version; } maxdsm;
static int lock_depth, fault, reads_from_dsp;
static void mutex_lock(int *m) { (void)m; lock_depth++; }
static void mutex_unlock(int *m) { (void)m; lock_depth--; }
static int dsm_fs_lock;
static void maxdsm_read_all(void)
{
	int i;

	reads_from_dsp++;
	for (i = 0; i < maxdsm.param_size; i++)
		maxdsm.param[i] = 0x1000 + i;
}
static unsigned long copy_to_user(void *to, const void *from, unsigned long n)
{
	if (fault)
		return n;
	memcpy(to, from, n);
	return 0;
}
#define pr_err(...) printf(__VA_ARGS__)
#define pr_info(...) printf(__VA_ARGS__)
#define EFAULT 14

#include "s9_dsm_snip.c"

static int fails;
#define CHECK(c, m) do { if (!(c)) { printf("FALHOU: %s\n", m); fails++; } else printf("ok: %s\n", m); } while (0)

int main(void)
{
	uint32_t store[64], buf[64];
	loff_t pos = 0;
	ssize_t r;

	maxdsm.param_size = 64;
	maxdsm.param = store;
	maxdsm.platform_type = 1;
	maxdsm.version = 51;

	r = maxdsm_read(NULL, (char *)buf, sizeof(buf), &pos);
	CHECK(r == (ssize_t)sizeof(buf), "leitura completa devolve 256 bytes (o original devolvia 0)");
	CHECK(buf[0] == 0x1000 && buf[63] == 0x1000 + 63, "conteudo copiado igual aos parametros");
	CHECK(pos == (loff_t)sizeof(buf), "posicao avancou");

	r = maxdsm_read(NULL, (char *)buf, sizeof(buf), &pos);
	CHECK(r == 0, "segunda leitura = fim de arquivo (cat nao entra em laco)");
	CHECK(reads_from_dsp == 1, "fim de arquivo nao consulta o DSP de novo");

	pos = 0;
	memset(buf, 0, sizeof(buf));
	r = maxdsm_read(NULL, (char *)buf, 16, &pos);
	CHECK(r == 16 && buf[4] == 0, "leitura curta copia so o pedido");

	pos = 0;
	fault = 1;
	r = maxdsm_read(NULL, (char *)buf, sizeof(buf), &pos);
	CHECK(r == -EFAULT && pos == 0, "falha de copia devolve -EFAULT");
	CHECK(lock_depth == 0, "trava liberada em todos os caminhos");

	if (fails) {
		printf("%d falhas\n", fails);
		return 1;
	}
	printf("TODOS OS TESTES DO DSM PASSARAM\n");
	return 0;
}

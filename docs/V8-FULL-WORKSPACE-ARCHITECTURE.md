# Unilaw v8 Full Workspace

A v8 transforma o aplicativo em um workspace local com roteamento automático de capacidades e uma superfície Work unificada.

## Política de empacotamento

Ferramentas leves e motores auxiliares ficam integrados ao APK. Pesos neurais grandes continuam em `.iapack` assinados. Empacotar Qwen General + Qwen Coder + Tiny-SD dentro de um APK único ultrapassaria aproximadamente 3 GiB e desfaria as melhorias de estabilidade/atualização da linha Corepacks.

## Capacidades integradas

- ferramentas exatas (cálculo, porcentagem, unidades, SHA-256, Base64)
- OCR latino offline já empacotado via ML Kit bundled
- leitura/indexação de PDF, imagens, texto, código, ZIP/APK e binários
- pesquisa local em projeto + Biblioteca Neural
- catálogo de análise APK/binária/logs
- backup/restauração de projetos
- ferramentas de imagem
- telemetria/root SM-G991B/Exynos 2100
- roteador automático para Qwen, Coder, Builder, Tiny-SD e ferramentas

## Work

- `Work • Offline`: Qwen + projeto + Biblioteca Neural, sem Internet, com estágios visíveis e persistência da entrega.
- `Work • Online`: companion separado com Internet, mantendo o APK principal sem `android.permission.INTERNET`.

## Segurança

O roteador nunca concede root a conteúdo gerado pelo LLM. Controles root continuam limitados a nós sysfs permitidos pelo perfil S21. Binários/APKs anexados são tratados como dados e nunca executados automaticamente.

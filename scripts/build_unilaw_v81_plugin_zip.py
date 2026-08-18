#!/usr/bin/env python3
import json
import zipfile
from pathlib import Path

profiles = [
    dict(id='tools.exact', name='Ferramentas Exatas', description='Calculadora, porcentagens, conversões, SHA-256 e Base64 sem gastar tokens do Qwen.', category='tool', automatic=True, threads=1, max_concurrent=2, thermal_soft_c=68, thermal_hard_c=72),
    dict(id='search.local', name='Pesquisa Local', description='Consulta projeto e Biblioteca Neural antes do Qwen.', category='document', automatic=True, threads=2, max_concurrent=1, thermal_soft_c=66, thermal_hard_c=70),
    dict(id='document.ocr', name='OCR Offline', description='Extrai texto de imagens e PDFs escaneados com modelo latino empacotado.', category='vision', automatic=True, threads=2, max_concurrent=1, thermal_soft_c=64, thermal_hard_c=69, max_image_dimension=1800),
    dict(id='vision.labels', name='Visão Leve', description='Rotula objetos/cenas localmente para enriquecer a pesquisa de imagens.', category='vision', automatic=True, threads=2, max_concurrent=1, thermal_soft_c=64, thermal_hard_c=69, max_image_dimension=1800),
    dict(id='barcode.qr', name='QR e Código de Barras', description='Lê QR, EAN, UPC e outros códigos das imagens anexadas sem internet.', category='vision', automatic=True, threads=1, max_concurrent=1, thermal_soft_c=64, thermal_hard_c=69, max_image_dimension=1800),
    dict(id='files.universal', name='Leitor Universal', description='PDF, texto, Markdown, código, ZIP/JAR/APK e binários em modo seguro.', category='document', automatic=True, threads=2, max_concurrent=1, thermal_soft_c=66, thermal_hard_c=70),
    dict(id='files.office', name='Office Local', description='DOCX, XLSX e PPTX: extrai texto pesquisável sem executar macros.', category='document', automatic=True, threads=2, max_concurrent=1, thermal_soft_c=66, thermal_hard_c=70),
    dict(id='files.epub', name='EPUB Reader', description='Importa livros EPUB para a Biblioteca Neural por capítulos/arquivos internos.', category='document', automatic=True, threads=2, max_concurrent=1, thermal_soft_c=66, thermal_hard_c=70),
    dict(id='structured.csvjson', name='CSV e JSON Analyzer', description='Valida JSON e resume linhas/colunas de CSV antes do contexto neural.', category='document', automatic=True, threads=1, max_concurrent=2, thermal_soft_c=68, thermal_hard_c=72),
    dict(id='database.sqlite', name='SQLite Inspector', description='Abre bancos somente para leitura, extrai esquema e pequenas amostras.', category='developer', automatic=True, threads=2, max_concurrent=1, thermal_soft_c=66, thermal_hard_c=70),
    dict(id='security.apk', name='APK Inspector', description='Pacote, SDK, permissões, certificado, hashes e conteúdo ZIP sem executar o APK.', category='developer', automatic=True, threads=2, max_concurrent=1, thermal_soft_c=66, thermal_hard_c=70),
    dict(id='developer.binary', name='Binary Inspector', description='Metadados, strings e amostra segura de arquivos binários.', category='developer', automatic=True, threads=1, max_concurrent=1, thermal_soft_c=67, thermal_hard_c=71),
    dict(id='developer.logcat', name='Logcat Analyzer', description='Analisa logs e stack traces; captura root somente quando solicitada.', category='developer', automatic=True, threads=1, max_concurrent=1, thermal_soft_c=67, thermal_hard_c=71),
    dict(id='backup.projects', name='Backup de Projetos', description='Exporta e restaura projetos, fontes, chats e checkpoints pelo SAF.', category='tool', automatic=False, threads=1, max_concurrent=1, thermal_soft_c=68, thermal_hard_c=72),
    dict(id='image.tools', name='Ferramentas de Imagem', description='Preparação, redimensionamento e conversão local de imagens.', category='vision', automatic=True, threads=2, max_concurrent=1, thermal_soft_c=64, thermal_hard_c=69, max_image_dimension=1800),
    dict(id='device.s21', name='S21 Exynos Manager', description='Perfil SM-G991B: térmico, zRAM, CPU/GPU e limites root seguros.', category='hardware', automatic=True, threads=1, max_concurrent=1, thermal_soft_c=64, thermal_hard_c=70),
    dict(id='model.qwen', name='Qwen General', description='Chat e Work Offline; pesado, um modelo neural principal por vez.', category='model', automatic=True, threads=3, max_concurrent=1, thermal_soft_c=63, thermal_hard_c=69, requires_pack_id='model.qwen35.2b'),
    dict(id='model.coder', name='Qwen Coder', description='Programação especializada; troca controlada de modelo quando necessário.', category='model', automatic=True, threads=3, max_concurrent=1, thermal_soft_c=63, thermal_hard_c=69, requires_pack_id='coder.qwen25.1_5b'),
    dict(id='model.tinysd', name='Tiny-SD', description='Geração de imagens; serial, proteção térmica e Vulkan experimental.', category='model', automatic=True, threads=2, max_concurrent=1, thermal_soft_c=62, thermal_hard_c=68, max_image_dimension=512, requires_pack_id='image.tinysd.q4k'),
]

out = Path('build/plugin-bundle-v81')
(out / 'profiles').mkdir(parents=True, exist_ok=True)
paths = []
for p in profiles:
    payload = {'schema': 1, **p}
    path = f"profiles/{p['id']}.json"
    paths.append(path)
    (out / path).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

bundle = {
    'schema': 1,
    'type': 'unilaw-capability-bundle',
    'id': 'unilaw.s21.essential.v81',
    'name': 'Unilaw Plugins Essenciais S21 v8.1',
    'version': '8.1.0',
    'target': 'Samsung Galaxy S21 5G SM-G991B / Exynos 2100',
    'profiles': paths,
}
(out / 'bundle.json').write_text(json.dumps(bundle, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(out / 'README.md').write_text(
    '# Unilaw Plugins Essenciais S21 v8.1\n\n'
    'Bundle declarativo: não contém código executável. Os perfis ativam/documentam capacidades já compiladas no Core e definem limites conservadores para o SM-G991B/Exynos 2100.\n\n'
    'Qwen, Coder e Tiny-SD continuam como .iapack separados.\n',
    encoding='utf-8',
)

zip_path = Path('Unilaw-Plugins-Essenciais-S21-v8-1.zip')
with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for f in sorted(out.rglob('*')):
        if f.is_file():
            z.write(f, f.relative_to(out).as_posix())

print(f'generated {zip_path} with {len(profiles)} profiles')

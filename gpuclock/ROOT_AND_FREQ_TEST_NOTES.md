# GPU Root & Frequency Test

Este módulo não explora vulnerabilidades nem faz root por conta própria. Ele detecta e solicita acesso `su` quando já fornecido por Magisk/KernelSU/APatch, mostra o estado do bootloader/Verified Boot e testa temporariamente apenas frequências expostas pelo kernel em `devfreq`, restaurando os limites originais ao final.

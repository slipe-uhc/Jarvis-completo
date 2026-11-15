# Jarvis (Glow Up)

Assistente de rotina com voz ("Jarvis"), treino, XP e avisos.

- `docs/` → versão do **PC** (e site). Fica no ar pelo GitHub Pages.
- `android/` → app **Android** nativo (escuta "Jarvis" em segundo plano).
- `.github/workflows/android.yml` → o GitHub compila o APK sozinho.

## 1. Gerar o APK (celular)
1. Suba tudo para um repositório novo (ex.: `slipe-uhc/jarvis`).
2. Aba **Actions** → espere o "Gerar APK do Jarvis" ficar verde (uns 4–6 min).
3. Aba **Releases** (lado direito da página do repo) → baixe `Jarvis.apk` pelo celular.
4. Instale (o Android pede para permitir "instalar apps desconhecidos" do navegador).

## 2. Liberar as permissões (uma vez)
Abra o app → aba **Ajustes** → "Permissões do celular", toque nos 4 botões e libere.
Depois, aba **Jarvis** → "Ativar escuta por voz". Vai aparecer a notificação fixa "Ouvindo Jarvis".

## 3. PC (.exe)
1. Na aba **Actions**, o "Gerar EXE do Jarvis (PC)" compila sozinho (uns 5 min).
2. Aba **Releases** → baixe `Jarvis-PC-Setup.exe` no PC e dê dois cliques.
3. O Windows pode mostrar "O Windows protegeu seu PC" (o app não é assinado): clique em **Mais informações → Executar assim mesmo**.
4. Pronto: instala sozinho, abre com o Windows (escondido na bandeja, perto do relógio) e fica ouvindo "Jarvis". Clique no ícone da bandeja para abrir a tela.
5. Na aba **Jarvis**, toque em "Ativar escuta por voz". Precisa do reconhecimento de fala do Windows em português (Configurações → Hora e idioma → Fala).

## 4. Ligar PC e celular
Em **Ajustes → Ligação PC e celular**, coloque o **mesmo código** nos dois e toque "Salvar código".
Daí "Jarvis, troque o ouvido para o PC" e "Jarvis, abre o YouTube e pesquise X no PC" funcionam entre os aparelhos.

## Comandos
- "Jarvis, abre o YouTube e pesquise Douglas Sola"
- "Jarvis, o que eu faço agora?" / "mostra o treino" / "marca como feito"
- "Jarvis, modo estudo | treino | silencioso | normal"
- "Jarvis, abre o Spotify"
- "Jarvis, troque o ouvido para PC | celular | ambos"
- Termine com "no PC" para mandar o comando para o outro aparelho.

## Limites
- Depois de **reiniciar o celular**, abra o Jarvis uma vez para religar a escuta.
- O Android só deixa abrir apps com o app fechado se "Aparecer sobre outros apps" estiver liberado; senão chega uma notificação "Toque para abrir".
- A escuta usa o reconhecimento de voz do Google; precisa de internet.

# CABAC : premiers résidus quantifiés — palier 23

Le lecteur Java passe désormais des mouvements aux résidus du **premier macrobloc inter non sauté de chaque slice P compatible**. Il lit coded_block_pattern, transform_size_8x8_flag lorsqu’il est présent, mb_qp_delta, coded_block_flag, la carte des coefficients significatifs, leur dernière position, les amplitudes et signes. L’ordre des coefficients est celui du scan syntaxique ; ils ne sont ni déquantifiés ni convertis en pixels.

## Résultats réellement exécutés

| Cas | Macroblocs lus | Positions de coefficients | Coefficients non nuls | Bits du préfixe reconstruits |
|---|---:|---:|---:|---:|
| whatsapp | 665 | 77016 | 5690 | 59981 |
| detail | 39 | 8392 | 2131 | 16283 |
| multi | 78 | 12552 | 2557 | 21896 |
| transform8 | 59 | 896 | 22 | 1621 |
| terminal | 19 | 4416 | 336 | 2200 |

**860 macroblocs, 103 272 positions, dont 10 736 coefficients non nuls : zéro écart avec la référence.** Les valeurs nulles sont également comparées : une position absente du journal des coefficients non nuls doit être zéro dans le lecteur Java. Les synthétiques sont des cas de couverture ; ils ne constituent pas quatre vidéos naturelles indépendantes.

La référence est FFmpeg 6.1.1, source officielle épinglée par SHA-256. Le patch journalise les valeurs quantifiées après lecture du signe, avant déquantification. Il appelle la primitive originale avec le même argument et retourne exactement sa valeur : seule la journalisation est ajoutée. Les décisions, probabilités, scans et pixels ne sont pas modifiés. Une exécution à **un thread** est obligatoire pour les variables de diagnostic. Le comparateur exclut les images décodées pendant l’analyse initiale du flux, associe les images et macroblocs, vérifie CBP/transformation/QP et toutes les positions de coefficients. Modifier un coefficient non nul, une position nulle ou le CBP provoque un rejet.

Le nouveau cas terminal (16×16) exerce **19 fins de slices à un**, après lecture de résidus ; les autres cas exercent les fins à zéro. Les nouveaux cas utilisent une surface animée sinusoïdale pour compléter la faible couverture des transformations 8×8 dans les deux anciens synthétiques. Les amplitudes jusqu’à 122 sont exercées sur les anciens cas, incluant l’échappement CABAC au-delà de 14.

## Reconstruction exacte : preuve limitée au préfixe

CabacPrefixReplay reconstruit **101 981 bits** du code arithmétique consommé, sans relire ces bits. À chaque décision, il entretient une borne inférieure entière ; le code initial consommé est récupéré avec cette borne et l’offset final du lecteur. Les bits reconstruits sont comparés un à un avec la source. Les en-têtes et les données non lues restent hors de cette preuve.

**Cette preuve utilise les bins et les états de probabilité observés par un diagnostic. Ce n’est pas un encodeur autonome à partir des seuls coefficients.** Elle indique notamment pourquoi conserver les seuls symboles ne suffit pas à choisir le même code arithmétique d’origine : la valeur finale de code doit aussi être traitée. Le test synthétique d’inversion couvre 200 flux, des états variés et 21 terminaisons ; 200 préfixes volontairement altérés sont rejetés.

## Limites et décision de compression

- Domaine : slices P progressives CABAC, huit bits, 4:2:0, SPS/PPS fixes, une slice par image, début à l’adresse zéro, macroblocs antérieurs sautés. Les blocs intra rencontrés dans une slice P ne sont pas prolongés jusqu’aux résidus.
- Les voisins précédemment sautés simplifient les contextes. Les macroblocs suivants exigent un historique complet des mouvements, résidus et modes. Les slices I/B, 4:2:2/4:4:4, entrelacé et transform bypass ne sont pas couverts par ce lecteur.
- Le repérage des en-têtes dépend toujours de trace_headers FFmpeg. Le format expérimental ne prétend pas être un parseur H.264 autonome complet.
- Les tables d’initialisation 0/1/2 sont présentes ; la couverture vidéo n’exerce pas nécessairement chacune de leurs combinaisons avec tous les QP.
- **Aucun nouveau gain de compression CABAC n’est mesuré.** Aucun nouvel encodeur n’est enregistré dans le routeur. Le meilleur MP4 du palier 22 reste 5 414 803 octets. La lecture et la reconstruction diagnostique du préfixe ne sont pas assimilées à un conteneur IVO exact sur une vidéo entière.

Pour la seule vidéo WhatsApp, les préfixes vérifiés couvrent 59 981 bits, soit environ 7 498 octets. Il faut étendre la syntaxe aux autres macroblocs avant de pouvoir évaluer un gain important sur ses 5,4 Mo.

## Reproduire

```bash
bash build-cabac-research.sh
bash test-cabac-first-skip.sh /chemin/source.mp4 /chemin/resultats --residuals
bash build-cabac-residual-reference.sh /chemin/construction-reference
/chemin/construction-reference/ffmpeg-6.1.1/ffmpeg -v debug -threads 1 -probesize 32 -analyzeduration 0 -f h264 -i /chemin/resultats/source.h264 -f null - > /chemin/resultats/reference.log 2>&1
java -cp target/classes:target/nal-classes:target/cabac-classes io.ivo.mp4.CabacResidualReferenceCheck /chemin/resultats/first-skip.csv.residuals.csv /chemin/resultats/reference.log 18 709
```

Les deux derniers nombres sont largeur en macroblocs et nombre d’images. Cas existants : WhatsApp 18/709, detail 6/40, multi 6/80. Nouveaux cas : transform8 8/60, terminal 1/20.

Les nouveaux MP4 ont été générés avec FFmpeg/libx264 : nullsrc puis geq, High yuv420p, QP24, preset veryslow ; bframes=0:threads=1:keyint=100:scenecut=0:partitions=none:ref=1:fast_pskip=0:8x8dct=1. Transform8 : 128×128, 15 images/s, 60 images, luminance `128+60*sin(X*0.08+T*0.5)+50*cos(Y*0.07+T)`, chromas 128. Terminal : 16×16, 15 images/s, 20 images, luminance `128+60*sin(X*0.3+T*4)+40*cos(Y*0.2+T*3)`, chromas 128. Les fichiers précis sont conservés, car une nouvelle version d’encodeur peut produire d’autres octets.

Résultats, sources des deux nouveaux cas, CSV, journaux, SHA-256 et validations : research-results/2026-10-05/mp4-cabac-residuals. Les sources H.264 des trois cas antérieurs restent dans mp4-cabac-motion. Aucun binaire tiers, JAR, .class ou .py inclus dans le livrable.

Références primaires : H.264 clause 9.3, https://www.itu.int/rec/T-REC-H.264 ; FFmpeg https://ffmpeg.org/doxygen/6.1/h264__cabac_8c_source.html ; archive https://ffmpeg.org/releases/ffmpeg-6.1.1.tar.xz (SHA-256 dans summary.json).

## Prochain obstacle concret

Maintenir les contextes des voisins pour les macroblocs suivants, couvrir leurs modes intra/inter, puis écrire la binarisation inverse et un encodeur CABAC exact. C’est seulement après une reconstruction autonome de slices entières que la recompression des cordes de coefficients pourra être comparée en coût IVO complet.

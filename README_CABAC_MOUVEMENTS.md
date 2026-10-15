# CABAC : références et différences de mouvement (version 12)

Le lecteur Java poursuit après mb_type et les quatre sub_mb_type : il lit les indices ref_idx_l0 puis les différences mvd_l0 horizontales et verticales de toutes les partitions du premier macrobloc inter non sauté. Les contextes 40..59 sont initialisés selon cabac_init_idc et QP, puis conservés entre partitions. Les signes et extensions utilisent le bypass CABAC. La géométrie des caches locaux 4×4 respecte les quatre sous-partitions ; les valeurs absolues de MVD sont plafonnées à 70 pour les contextes voisins, comme dans la référence. Tous les voisins externes parcourus sont sautés ou indisponibles.

## Résultats exécutés

| Cas | Paires MVD | Composantes MVD | Références comparées |
|---|---:|---:|---:|
| WhatsApp originale | 912 | 1 824 | 912 (indice 0) |
| Synthétique détaillé, une référence | 100 | 200 | 100 (indice 0) |
| Synthétique détaillé, plusieurs références | 231 | 462 | 231 (indices 0 à 4) |
| Total | 1 243 | 2 486 | 1 243 |

Zéro écart, y compris les deux sommes des MVD voisins servant à choisir les contextes. Les valeurs proviennent directement de FFmpeg 6.1.1 instrumenté au macro DECODE_CABAC_MB_MVD, avant modification des caches. Il ne s’agit pas de comparer les vecteurs finaux AVMotionVector. La référence reçoit uniquement le flux H.264, jamais les résultats Java. Le comparateur exige une correspondance unique pour chaque clé image/macrobloc/bloc ; trois CSV aux MVD altérés sont rejetés. 312 vecteurs prescrits couvrent les seuils 2/3 et 32/33, les amplitudes 8/9, les extensions et les signes ; 17 vecteurs couvrent la référence. Ces tests prescrits ne remplacent pas les comparaisons externes.

Les tests généraux du projet passent également. Traces, CSV et contrôles sont dans research-results/2026-10-04/mp4-cabac-motion. Les initialisations 0 et 1 sont présentes dans le corpus ; l’initialisation 2 n’est pas validée ici sur une vidéo réelle. Le cas multi-références est synthétique, créé avec libx264 ref=4 ; les listes actives peuvent inclure une référence pondérée supplémentaire.

## Domaine et limites

Même domaine que le jalon précédent : slices P progressives huit bits, départ macrobloc zéro, une slice par image, SPS/PPS constants. Le lecteur s’arrête après les mouvements du premier macrobloc non sauté, et avant les indicateurs de résidu. Les macroblocs intra s’arrêtent après mb_type. Il ne reconstruit pas encore les vecteurs prédits ni les pixels. L’analyse des en-têtes utilise la trace FFmpeg. Ce lecteur reste hors routeur de compression.

Aucun nouveau conteneur ni gain CABAC mesuré. Le meilleur précédent reste 5 414 825 octets pour la vidéo WhatsApp. La réémission CABAC exacte n’est pas réalisée.

## Reproduire

```bash
bash test-cabac-first-skip.sh /chemin/video.mp4 /chemin/resultats --motion
bash build-cabac-subtype-reference.sh /chemin/reference
/chemin/reference/ffmpeg-6.1.1/ffmpeg -v debug -threads 1 -probesize 32 -analyzeduration 0 -f h264 -i /chemin/resultats/source.h264 -f null - > /chemin/resultats/reference.log 2>&1
java -cp target/classes:target/nal-classes:target/cabac-classes io.ivo.mp4.CabacMotionReferenceCheck /chemin/resultats/first-skip.csv.motion.csv /chemin/resultats/reference.log 18
```

Largeur en macroblocs : 18 pour WhatsApp, 6 pour les deux cas synthétiques. Le script de référence journalise maintenant les sous-types et les différences de mouvement sans modifier les décisions. Source officielle : https://ffmpeg.org/releases/ffmpeg-6.1.1.tar.xz ; syntaxe : H.264 clause 9.3, https://www.itu.int/rec/T-REC-H.264 . Aucun binaire tiers inclus.

Génération du nouveau cas : testsrc2 256×192 à 15 images/s, crop 96×96:40:30, 80 images, libx264 preset veryslow QP18 yuv420p, bframes=0:threads=1:keyint=100:scenecut=0:partitions=all:ref=4:fast_pskip=0. Le MP4 exact est conservé.

## Suite de recherche

Prochain décodage : coded_block_pattern, éventuel transform_size_8x8_flag, mb_qp_delta, puis positions et amplitudes des coefficients. Il faudra ensuite parcourir tous les macroblocs, traiter les autres catégories de slices et reconstruire le flux original octet pour octet.

Hypothèses d’optimisation, sans gain démontré : N&C temporel sur mouvements et coefficients ; REF sur motifs structuraux réellement répétés ; contextes continus entre slices. Chaque candidat devra compter l’intégralité des métadonnées et battre le flux CABAC original ou le meilleur conteneur existant sur plusieurs vidéos. La priorité de potentiel est aux coefficients/résidus ; les petits gains d’en-têtes ne démontrent pas ce potentiel.

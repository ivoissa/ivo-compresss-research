# CABAC : suites initiales de macroblocs sautés

## Explication simple

Le lecteur poursuit désormais le décodage après un premier macrobloc sauté. Il lit son drapeau de fin de slice, puis le drapeau de saut du macrobloc suivant. Il s’arrête dès qu’un macrobloc n’est pas sauté ou que la slice se termine.

Sur la vidéo originale : 910 drapeaux de saut et 204 drapeaux de fin contrôlés, zéro écart. La plus longue suite initiale contient six macroblocs sautés. Deux petites vidéos synthétiques complètent le test, dont une image constante qui atteint la fin de neuf slices entièrement sautées.

Aucun nouveau fichier .ivo n’est produit. Le meilleur conteneur précédent reste 5 414 825 octets. Ces résultats valident la lecture d’un préfixe de syntaxe, pas un nouveau moteur de compression vidéo.

## Partie technique

CabacFirstSkip accepte maintenant le mode --prefix ; son mode historique continue à lire un seul drapeau par slice P. Les grilles FFmpeg conservent tous leurs marqueurs, dans l’ordre raster des macroblocs. Le domaine reste limité à une slice par image, adresse initiale zéro, vidéo progressive et CABAC, images I/P, SPS/PPS identiques d’identifiant zéro.

Tant que tous les macroblocs déjà décodés sont sautés, un voisin gauche ou supérieur disponible contribue zéro à ctxIdxInc. Un voisin indisponible contribue également zéro. Le contexte reste donc **ctxIdx=11**, même lorsque le parcours passe à la ligne suivante. Le contexte est initialisé une seule fois par slice et son état évolue après chaque décision ; il n’est pas réinitialisé par macrobloc.

Les paires (m,n) du contexte 11 sont : (23,33) pour cabac_init_idc=0 ; (22,25) pour 1 ; (29,16) pour 2. Les tables 0 et 1 ont été rencontrées dans ces tests réels/synthétiques. La paire 2 est recoupée avec la référence numérique mais n’a pas été exercée sur une vidéo de ce corpus. Il ne s’agit pas des tables complètes d’initialisation H.264.

Pour chaque macrobloc : décoder mb_skip_flag et le comparer à la grille. Si zéro, arrêter avant mb_type et les autres éléments de syntaxe. Si un, décoder end_of_slice_flag avec la primitive de terminaison ; continuer seulement si zéro. La référence de fin est déduite de l’adresse du dernier macrobloc de l’image dans ce domaine à une seule slice, pas d’une trace externe de bins de terminaison. Les dimensions complètes des grilles, les comptes de slices et d’images et leurs types sont contrôlés.

Le mode --prefix atteint la fin logique des slices entièrement sautées. Cela ne vérifie pas les bits restants, le remplissage ni une réémission CABAC exacte. Il ne permet pas de reprendre après un macrobloc non sauté, puisque sa syntaxe n’est pas décodée.

## Mesures exécutées

| Cas | Images | Drapeaux de saut comparés | Sauts | Drapeaux de fin | Fins égales à un | Suite maximale |
|---|---:|---:|---:|---:|---:|---:|
| Vidéo WhatsApp | 709 | 910 | 204 | 204 | 0 | 6 |
| Synthétique constant 64 × 64 | 10 | 144 | 144 | 144 | 9 | 16 |
| Synthétique détaillé 96 × 64 | 12 | 23 | 12 | 12 | 0 | 4 |

Total : 1 077 drapeaux de saut comparés et 360 décisions de terminaison, zéro écart. Altérer le marqueur du deuxième macrobloc d’une image P synthétique déclenche un rejet, alors que le premier marqueur reste correct. Les tests arithmétiques et les contrôles existants du projet sont aussi exécutés par le script.

Les deux MP4 synthétiques, journaux, CSV et récapitulatif sont dans research-results/2026-10-04/mp4-cabac-prefix. Le journal FFmpeg de la vidéo originale est dans mp4-cabac-first-skip, sa trace d’en-tête dans mp4-slice-headers. Les résultats antérieurs sont historiques ; le nouveau CSV ajoute l’adresse du macrobloc et le drapeau de fin (-1 lorsque non lu).

## Reproduire

```bash
bash test-cabac-first-skip.sh /chemin/original.mp4 /chemin/resultats --prefix
```

Pour générer le cas constant : FFmpeg lavfi color=c=black:s=64x64:r=10, dix images, libx264 High yuv420p QP22, paramètres bframes=0:threads=1:keyint=100:scenecut=0. Pour le cas détaillé : testsrc2=s=96x64:r=10, douze images et mêmes paramètres.

Sources primaires : H.264 clause 9.3, https://www.itu.int/rec/T-REC-H.264 ; constantes d’initialisation et contexte de saut recoupés avec https://www.ffmpeg.org/doxygen/4.2/h264__cabac_8c_source.html . Le lecteur Java reste une expérience hors routeur.

## Prochaine étape

Décoder mb_type du premier macrobloc non sauté et comparer sa catégorie à la grille FFmpeg. Les mouvements, modes de prédiction et résidus doivent ensuite être traités pour avancer au macrobloc suivant. La réémission exacte reste obligatoire avant toute recherche de gains sur les cordes CABAC.

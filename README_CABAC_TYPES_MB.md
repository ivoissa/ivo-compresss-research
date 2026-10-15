# CABAC : type du premier macrobloc non sauté

## Explication simple

Après les macroblocs sautés initiaux, le lecteur Java lit maintenant mb_type du premier macrobloc non sauté des slices P. Il retrouve sa catégorie et, pour les macroblocs inter, la forme des partitions. Sur la vidéo originale, les 706 catégories/partitions concordent avec FFmpeg. Le cas synthétique détaillé ajoute onze comparaisons sans écart.

Il manque toujours les mouvements, les modes de prédiction, les sous-types des partitions 8×8 et les résidus pour poursuivre jusqu’au macrobloc suivant. Aucun nouveau conteneur .ivo n’est produit et aucun gain n’est annoncé. Le meilleur résultat précédent reste 5 414 825 octets.

## Partie technique

CabacMbType utilise les contextes 14 à 20, initialisés une fois à partir de cabac_init_idc et SliceQPY. Ils n’ont pas encore été utilisés par les drapeaux de saut du préfixe. Le lecteur reçoit le même objet CabacArithmetic que ces drapeaux ; range, offset et position du lecteur restent continus.

L’arbre de binarisation des slices P distingue : P_L0_16×16 (valeur 0), P_L0_16×8 (1), P_L0_8×16 (2), P_8×8 (3), puis les catégories intra. Dans la branche intra, une décision au contexte 17 distingue I_NxN ; une décision de terminaison distingue I_PCM ; sinon les décisions 18..20 déterminent le type intra 16×16. Cette terminaison interne à mb_type n’est pas un end_of_slice_flag : les CSV les séparent.

Pour I_16×16 dans une slice P :

```
mb_type = 6 + 12*cbpLumaNonzero + 4*cbpChroma + predictionMode
cbpChroma ∈ {0,1,2}, predictionMode ∈ {0,1,2,3}
```

La valeur complète est calculée et enregistrée. Toutefois, la référence externe mb_type de FFmpeg n’expose ici que la catégorie I_16×16, pas ses indicateurs CBP ni son mode de prédiction. Leur calcul n’est donc pas déclaré indépendamment validé sur vidéo. I_NxN est également une catégorie : le drapeau de transformation qui distingue 4×4 et 8×8 n’est pas encore lu.

La grille de référence conserve désormais les caractères de segmentation (espace, -, |, +), en plus des catégories. Cela permet de comparer les quatre formes inter. P_8×8 indique la partition principale, pas les sub_mb_type. Le lecteur s’arrête immédiatement après mb_type ; il ne réinitialise ni ne réutilise arbitrairement son état pour le macrobloc suivant.

Le domaine reste celui de l’essai précédent : vidéo progressive huit bits, CABAC, une slice par image, première adresse zéro, SPS/PPS identiques d’identifiant zéro, images I/P. Les tables d’initialisation sont partielles (contextes nécessaires à cette expérience). Les tables 0 et 1 sont exercées sur le corpus ; la table 2 n’a pas de couverture vidéo dans cet essai.

## Validation réelle

| Catégorie | Vidéo originale | Synthétique détaillé | Total |
|---|---:|---:|---:|
| P_L0_16×16 | 524 | 9 | 533 |
| P_L0_16×8 | 44 | 0 | 44 |
| P_L0_8×16 | 44 | 0 | 44 |
| P_8×8 | 53 | 2 | 55 |
| I_NxN | 6 | 0 | 6 |
| I_16×16 | 35 | 0 | 35 |

717 catégories/partitions comparées, zéro écart. Le cas synthétique constant conserve ses neuf fins de slices entièrement sautées ; aucun mb_type non sauté n’y est lu. Modifier seulement la segmentation du premier macrobloc inter de la référence déclenche un rejet, sans modifier son drapeau de saut.

CabacMbTypeTest vérifie neuf chemins fixes de l’arbre, avec contrôle de l’ordre et des indices de contexte, dont la branche I_PCM et des bornes intra 16×16. Il s’agit de vecteurs de syntaxe prescrits, pas de vecteurs arithmétiques fournis par un codec externe. Aucun I_PCM n’est rencontré dans les vidéos de cet essai.

Les CSV et résultats sont dans research-results/2026-10-04/mp4-cabac-mb-types. Les vidéos synthétiques et leurs traces de référence restent dans mp4-cabac-prefix ; la référence de la vidéo originale est conservée dans mp4-cabac-first-skip et mp4-slice-headers.

## Reproduire

```bash
bash test-cabac-first-skip.sh /chemin/original.mp4 /chemin/resultats --mb-types
```

Le script compile et teste le projet puis produit first-skip.csv et first-skip.csv.types.csv. Ce dernier conserve les décisions et indices de contextes parcourus, le type calculé, la catégorie de référence et l’état arithmétique après lecture. Ces positions de lecture ne constituent pas des tailles de compression.

Références primaires : H.264 clauses 7.4 et 9.3, https://www.itu.int/rec/T-REC-H.264 ; recoupement des constantes numériques et des décisions via https://www.ffmpeg.org/doxygen/4.2/h264__cabac_8c_source.html . Les fonctions C ne sont pas incorporées au projet ; l’implémentation Java est une expérience de lecture de la syntaxe normative.

## Prochaine étape

Commencer les éléments de prédiction des macroblocs inter : sub_mb_type lorsqu’il y a des partitions 8×8, indices de références et différences de mouvement. Le décodage complet des résidus et la réémission octet pour octet restent nécessaires avant une mesure de compression CABAC.

# CABAC : sous-partitions du premier macrobloc P_8×8

## Explication simple

Le lecteur Java lit maintenant les quatre sub_mb_type du premier macrobloc non sauté lorsqu’il est de type P_8×8. Sur la vidéo originale, 212 valeurs sont lues. Un cas synthétique détaillé ajoute 44 valeurs et exerce les quatre formes possibles. Ces **256 valeurs concordent directement avec les décisions d’un décodeur FFmpeg instrumenté**, sans écart.

Ce résultat valide une partie supplémentaire du décodage. Il ne produit pas de conteneur .ivo plus petit. Les références d’images, mouvements, résidus et réémission exacte restent à construire ; le meilleur résultat précédent demeure 5 414 825 octets.

## Partie technique

CabacSubType utilise l’arbre de binarisation des slices P :

| Valeur | Forme | Décisions |
|---|---|---|
| 0 | 8×8 | contexte 21 : 1 |
| 1 | 8×4 | contexte 21 : 0 ; 22 : 0 |
| 2 | 4×8 | contexte 21 : 0 ; 22 : 1 ; 23 : 1 |
| 3 | 4×4 | contexte 21 : 0 ; 22 : 1 ; 23 : 0 |

CabacMbType.Input initialise désormais les contextes 14..23, à partir des coefficients normatifs nécessaires et du QP. Les états des contextes 21..23 sont partagés entre les quatre sous-macroblocs ; ils ne sont pas réinitialisés entre deux lectures. Le même lecteur arithmétique continue depuis mb_type, avec range, offset et position inchangés avant la première décision supplémentaire.

Ordre des sous-macroblocs : haut gauche, haut droite, bas gauche, bas droite. Le CSV enregistre valeur, dimensions, origine du carré 8×8, décisions parcourues et état arithmétique après chaque lecture. Les quatre sub_mb_type sont lus avant les indices de référence et les différences de mouvement. Le programme s’arrête à cette frontière.

Le domaine reste restreint aux slices P progressives à adresse initiale zéro, une slice par image, huit bits de luminance, CABAC et SPS/PPS identiques. Le calendrier de lecture ne s’étend pas aux slices B. Les tables de contextes restent partielles ; seules les initialisations 0 et 1 sont exercées sur ces vidéos.

## Référence indépendante et limite découverte

Une première tentative a comparé les rectangles de l’export AVMotionVector. Cet export regroupe les partitions principales en 8×8 ; il ne constitue pas une référence des subdivisions 8×4, 4×8 ou 4×4. Les 14 différences observées sur le cas détaillé ont révélé cette insuffisance. Les concordances de géométrie initiales ne sont pas retenues comme preuve de sub_mb_type.

La validation retenue utilise FFmpeg 6.1.1, construit depuis l’archive source officielle dont le SHA-256 est conservé dans summary.json. Deux messages sont ajoutés : un repère au macrobloc (0,0), puis la valeur sub_mb_type immédiatement après son décodage et avant sa conversion en indicateurs internes. Les décisions, tables et état du décodeur tiers ne sont pas modifiés. Les traces du décodage effectué pendant la recherche initiale d’informations sont exclues ; le test prend uniquement la phase après Stream mapping, avec un thread.

CabacSubTypeReferenceCheck associe (image, colonne de macrobloc, ligne, indice de sous-macrobloc), exige chaque correspondance exactement une fois et compare directement les valeurs 0..3. Modifier une valeur Java du CSV provoque un rejet. Les quatre chemins de syntaxe sont aussi contrôlés par CabacSubTypeTest ; ces vecteurs prescrits ne remplacent pas la comparaison externe sur vidéo.

## Résultats réellement exécutés

| Forme | Vidéo originale | Synthétique détaillé | Total |
|---|---:|---:|---:|
| 8×8 | 212 | 30 | 242 |
| 8×4 | 0 | 3 | 3 |
| 4×8 | 0 | 5 | 5 |
| 4×4 | 0 | 6 | 6 |

256 comparaisons directes, zéro écart. La couverture des trois formes fines est petite et synthétique : cela ne prouve pas un décodeur universel H.264. Le cas détaillé comporte 40 images ; la vidéo originale en comporte 709. Le contrôle antérieur des catégories/partitions du premier macrobloc reste exécuté avant les sous-types.

Les CSV, journaux complets du décodeur de référence, configuration de compilation, résultats et MP4 synthétique sont dans research-results/2026-10-04/mp4-cabac-subtypes. Aucun binaire tiers n’est incorporé au livrable. Le lecteur Java reste hors routeur de compression.

## Reproduire

```bash
bash test-cabac-first-skip.sh /chemin/original.mp4 /chemin/resultats --sub-types
bash build-cabac-subtype-reference.sh /chemin/construction-reference
/chemin/construction-reference/ffmpeg-6.1.1/ffmpeg -v debug -threads 1 -probesize 32 -analyzeduration 0 -f h264 -i /chemin/resultats/source.h264 -f null - > /chemin/resultats/reference.log 2>&1
java -cp target/classes:target/nal-classes:target/cabac-classes io.ivo.mp4.CabacSubTypeReferenceCheck /chemin/resultats/first-skip.csv.subtypes.csv /chemin/resultats/reference.log 18
```

Le dernier argument est la largeur en macroblocs : 18 pour la vidéo originale, 6 pour le cas synthétique 96×96. La construction du décodeur tiers requiert réseau, curl, tar, Python 3, compilateur C et make ; le lecteur et le comparateur sont Java 17.

Génération du cas détaillé : lavfi testsrc2=s=256x192:r=15, crop=96:96:40:30, 40 images, libx264 preset veryslow High yuv420p QP18, paramètres bframes=0:threads=1:keyint=100:scenecut=0:partitions=all:ref=1:fast_pskip=0. Le MP4 exact testé est conservé pour éviter les variations entre versions d’encodeur.

Références primaires : H.264 clause 9.3, https://www.itu.int/rec/T-REC-H.264 ; constantes et binarisation recoupées avec https://www.ffmpeg.org/doxygen/4.2/h264__cabac_8c_source.html ; source de référence https://ffmpeg.org/releases/ffmpeg-6.1.1.tar.xz . Il s’agit de syntaxe H.264 connue ; l’originalité recherchée pour IVO concernera les cordes et modèles de recompression ultérieurs.

## Prochaine étape

Décoder les indices des images de référence puis les différences de mouvement, avec des contextes causaux corrects. Le décodeur tiers instrumenté fournit maintenant un moyen de comparer directement ces valeurs. Les slices complètes et la réémission octet pour octet précèdent toute annonce de gain CABAC.

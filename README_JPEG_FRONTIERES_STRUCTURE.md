# JPEG : catégories AC et présence prédites aux frontières — version 17

Ce palier utilise le prédicteur physique de frontière pour coder la catégorie des coefficients AC et leur caractère nul/non nul. Il conserve les coefficients quantifiés, les DC, les métadonnées et le patch exact du flux JPEG. Les signes utilisent le mode 3 et les amplitudes le mode 5 des paliers précédents. La reconstruction vise le fichier JPEG complet, octet pour octet.

## Résultats au coût IVO complet

Les mêmes 30 JPEG qu'à v16 sont testés (16 initiaux, dont 4 synthétiques, et 14 exemples complémentaires du dépôt exif-samples). Quatre variantes produisent **120 nouveaux candidats**. Le témoin est le meilleur résultat réellement retenu à v16 pour chaque fichier, vérifié en le décompressant avant toute mesure. Le nom du fichier dans les conteneurs est conservé.

Avec sélection du plus petit candidat ou du résultat v16, le volume cumulé passe de **11 589 959 à 11 322 768 octets** : **267 191 octets supplémentaires économisés, soit 2,31 %**. Seize fichiers gagnent ; quatorze conservent v16.

Sur les 16 fichiers initiaux : 155 532 octets gagnés et dix améliorations. Sur les 14 complémentaires : 111 659 octets gagnés et six améliorations. Les pourcentages concernent les volumes cumulés de ce corpus, après sélection, sans extrapolation à tous les JPEG ni modification d'une moyenne calculée ailleurs. Ces fichiers complémentaires ne constituent plus un corpus tenu à l'écart : ils ont déjà servi à mesurer les modèles v16.

| Fichier amélioré | Meilleur v16 | Nouveau candidat | Gain | Mode |
|---|---:|---:|---:|---:|
| 01-25060.jpg | 169926 | 169080 | 846 | 9 |
| 01-25066.jpg | 94329 | 93714 | 615 | 9 |
| Nikon_D70.jpg | 12284 | 12248 | 36 | 9 |
| Reconyx_HC500_Hyperfire.jpg | 366166 | 356364 | 9802 | 9 |
| fixture-2.jpg | 938 | 869 | 69 | 10 |
| fixture-3.jpg | 3131 | 2996 | 135 | 10 |
| hdr_canon_hdr_NO.jpg | 664401 | 650070 | 14331 | 9 |
| hdr_iphone_hdr_NO.jpg | 1575113 | 1520247 | 54866 | 9 |
| hdr_iphone_hdr_YES.jpg | 1594612 | 1539895 | 54717 | 9 |
| mobile_jolla.jpg | 649550 | 629435 | 20115 | 9 |
| Canon_40D_photoshop_import.jpg | 9759 | 9635 | 124 | 9 |
| Canon_PowerShot_S40.jpg | 31740 | 30783 | 957 | 9 |
| gps_DSCN0010.jpg | 142224 | 140533 | 1691 | 9 |
| gps_DSCN0012.jpg | 143724 | 142371 | 1353 | 9 |
| mobile_HMD_Nokia_8.3_5G.jpg | 1789947 | 1757227 | 32720 | 9 |
| mobile_HMD_Nokia_8.3_5G_hdr.jpg | 4224993 | 4150179 | 74814 | 9 |

Quatorze gains utilisent le modèle physique conjoint (mode 9). Deux cas synthétiques préfèrent le témoin de présence sans prédiction physique (mode 10) : leurs gains ne sont pas attribués au prédicteur de frontière.

## Variantes et témoins

| Mode | Catégories | Présence |
|---|---|---|
| 7 | Sixième expert avec catégorie prédite aux frontières | Six cordes SUPPORT historiques |
| 8 | Témoin : sixième expert par bande uniquement | Six cordes SUPPORT historiques |
| 9 | Même prédiction de catégorie que le mode 7 | Modèle binaire causal avec catégorie prédite et voisins |
| 10 | Même prédiction de catégorie que le mode 7 | Témoin binaire avec voisins et bandes, sans catégorie prédite |

Sur l'ensemble des 30 essais appariés, le mode 7 économise 124 765 octets par rapport au mode 8, et le mode 9 économise 74 911 octets par rapport au mode 10. Ces différences brutes isolent respectivement l'information de prédiction de catégorie et celle de présence ; elles ne s'additionnent pas au gain retenu de 267 191 octets. Certains petits fichiers préfèrent les témoins. Le nouveau format de présence réduit aussi le nombre de champs et change le modèle de codage, ce qui explique des gains indépendants du prédicteur physique.

## Modèle causal

Le prédicteur reste la projection DCT partielle de `BoundarySigns`, avec les tables DQT originales, les DC déjà décodés et les coefficients AC déjà reconstruits. Le parcours est par fréquence zigzag et par blocs en serpentin.

Le modèle de catégories conserve les cinq experts du backend qualité (voisinage, fréquence précédente, voisins horizontal/supérieur et similarité) et ajoute un sixième expert, indexé par bande et catégorie de la valeur prédite, plafonnée à 10. Une mixture adaptative entière à partage fixe ajuste les six poids dans les trois groupes de qualité du modèle de similarité. Le témoin 8 utilise exactement cette même mixture, avec une catégorie prédite fixée à zéro. Les dix symboles restent les catégories non nulles 1 à 10.

Le modèle de présence conserve la profondeur K de chaque bloc : les fréquences au-delà de K sont implicitement nulles, et le coefficient à K est implicitement présent. Pour les fréquences inférieures à K, le modèle binaire mélange un repli bande/fréquence précédente avec un contexte composé de la catégorie prédite (plafonnée à 5) et de la présence des voisins horizontal/supérieur. Le témoin 10 remplace la catégorie prédite par zéro. Une seule corde binaire remplace les six cordes SUPPORT historiques.

Le décodeur conjoint lit, pour chaque emplacement actif : la présence si nécessaire, la catégorie si le coefficient existe, ses bits d'amplitude et son signe. Il rend ensuite ce coefficient disponible au prédicteur des suivants. Les catégories et signes futurs ne sont jamais utilisés. Les flux restent physiquement séparés dans le payload ; ils sont consommés dans cet ordre causal. Les contextes et probabilités sont réinitialisés par composante.

## Validation

**150 archives** ont été décompressées dans des processus CLI distincts et comparées octet pour octet aux sources : les 120 nouveaux candidats et les 30 références v16. Quatre archives tronquées, une par nouveau mode, sont rejetées. Une référence appartenant à un autre JPEG est également rejetée par le programme de mesure.

Le build complet Java 17 passe. Le nouveau test de catégorie couvre 50 000 symboles, un changement de distribution, des renormalisations de compteurs, les trois groupes de qualité et six entrées invalides. Les tests précédents des signes, amplitudes et autres formats restent actifs. Les quatre JPEG synthétiques couvrent notamment les petites géométries en niveaux de gris, la scène lisse et le bruit. Le chemin de reproduction sans référence fournie confirme les tailles de fixture-3.

Mesures, tableaux complets, empreintes SHA-256 des sources, logs et validations : `research-results/2026-10-04/jpeg-structure`. Les temps comprennent des reconstructions et ne constituent pas un benchmark à froid.

## Reproduire

Java 17, depuis la racine :

```sh
bash build.sh
java -Xmx2g -cp target/classes io.ivo.jpeg.JpegStructureMeasure original.jpg resultats
java -Xmx1g -cp target/classes io.ivo.cli.IvoCli decompress resultats/structure-m9.ivo restaure.jpg
```

Sans troisième argument, le programme recalcule le portefeuille v16 via les modèles précédents, ce qui nécessite plusieurs encodages. Pour réutiliser une référence v16 déjà calculée :

```sh
java -Xmx2g -cp target/classes io.ivo.jpeg.JpegStructureMeasure original.jpg resultats reference-v16.ivo
```

La référence doit restituer exactement le fichier source et conserver le même nom dans le conteneur. Le programme écrit `previous-v16-best.ivo` et les quatre variantes `structure-m7.ivo` à `structure-m10.ivo`, avec les gains au coût final.

Le décodeur expérimental intégré (moteur 24, JV87 / C85) accepte les modes 7 à 10, en conservant les modes antérieurs. Les modes 9/10 changent l'organisation des champs SUPPORT et nécessitent ce décodeur récent ; v16 ne les lit pas. La compression automatique conserve son portefeuille antérieur. Le support reste limité aux JPEG baseline Huffman 8 bits acceptés par le parseur, sans promesse générale pour les progressifs ou multiscans.

Le paquet distribué ne contient aucun `.jar`, `.py` ni `.class`. Le build produit ses fichiers compilés localement.

## Suite utile

Ce palier justifie une validation sur un corpus JPEG externe plus varié avant promotion automatique. Une autre hypothèse de modèle est de distinguer la confiance de la projection (accord des deux frontières, fréquence et quantification), plutôt que d'utiliser seulement la catégorie prédite. Elle n'est pas implémentée ici et aucun gain n'est garanti.

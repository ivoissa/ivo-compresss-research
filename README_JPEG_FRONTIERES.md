# JPEG : prédiction causale aux frontières des blocs — version 15

Ce palier améliore le codage des signes AC, en conservant les amplitudes du palier spatial précédent (mode 1, backend qualité), les catégories, supports, DC et le patch exact du flux JPEG. Aucun coefficient n'est modifié. Le format reste lossless au niveau du fichier complet.

## Résultat mesuré

Sur les mêmes 16 JPEG (10 publics, 2 captures utilisateur, 4 synthétiques), deux modèles de frontière et un témoin sans frontière sont testés : **32 candidats et 16 témoins**. Sept fichiers non synthétiques gagnent ; neuf conservent leur résultat v14. Aucun cas synthétique ne bat le portefeuille v14.

Avec sélection du meilleur candidat frontière ou du résultat v14, le volume cumulé passe de **5 412 318 à 5 210 570 octets**, soit **201 748 octets supplémentaires économisés (3,73 %)**. Ce pourcentage est une réduction pondérée des archives de ce petit corpus, après sélection ; il ne constitue pas une moyenne universelle ni une augmentation d'une moyenne provenant d'un autre corpus.

| Fichier amélioré | Meilleur v14 | Témoin sans frontière | Frontière | Gain / v14 |
|---|---:|---:|---:|---:|
| 01-25060.jpg | 171403 | 171360 | 169955 | 1448 |
| 01-25066.jpg | 94696 | 94654 | 94356 | 340 |
| Reconyx_HC500_Hyperfire.jpg | 378957 | 378889 | 366697 | 12260 |
| hdr_canon_hdr_NO.jpg | 675615 | 675572 | 665479 | 10136 |
| hdr_iphone_hdr_NO.jpg | 1652706 | 1652099 | 1578126 | 74580 |
| hdr_iphone_hdr_YES.jpg | 1670996 | 1670481 | 1597608 | 73388 |
| mobile_jolla.jpg | 681165 | 681099 | 651569 | 29596 |

Les cinq grandes photos préfèrent le mode 3 ; les deux captures préfèrent le mode 2. Les petits exemples publics restent moins bons que leur référence. Le témoin emploie la même mixture et les bandes de fréquence, sans les informations de prédiction : les gains principaux persistent face à ce témoin.

## Modèle

Le parcours est celui des fréquences JPEG en zigzag, puis des blocs en serpentin. Pour le coefficient courant, on projette l'écart entre les bords horizontal et supérieur sur sa fonction de base DCT. On emploie les coefficients quantifiés déjà reconstruits, les DC déjà décodés et les tables DQT originales. Les voisins disponibles fournissent aussi leur coefficient à la fréquence courante. Les fréquences supérieures non encore reconstruites sont exclues. Aucun futur signe n'est consulté.

La prédiction minimise un écart quadratique de frontière avec cette reconstruction partielle. Elle approxime les valeurs de bord par les constantes entières normalisées [181, 251, 237, 213, 181, 142, 98, 50] et leur alternance de signe au bord opposé. Elle ne calcule pas une image complète et n'utilise aucun réencodage ImageIO.

Le signe prédit, sa force et la bande fréquentielle forment le contexte spatial. Le mode 2 mesure la force relativement à l'amplitude déjà décodée ; le mode 3 utilise la catégorie de la valeur prédite. Le mode 4 est le témoin : prédiction et force fixées à zéro, contexte par bande seulement. Une mixture bayésienne entière avec partage fixe combine ce contexte avec le contexte historique à quatre états (voisins positifs, négatifs, mixtes ou absents). Les probabilités sont apprises à partir des signes exacts. Un modèle est réinitialisé par composante.

Les calculs ordinaires utilisent des entiers longs ; une branche BigInteger évite les débordements sur des projections extrêmes. Les tables DQT sont retrouvées dans les métadonnées déjà conservées, sans ajout de tables au payload.

## Validation et limites

72 archives ont été reconstruites à l'identique par le CLI dans des processus distincts : les 48 nouveaux essais et 24 archives antérieures de régression. Deux nouvelles archives tronquées sont rejetées. Le build complet passe. Les tests unitaires du modèle couvrent 108 cas et 27 201 signes, les géométries horizontale/verticale/sans voisin, les DQT 8/16 bits, les grandes valeurs, le chemin de débordement et trois métadonnées invalides. Le programme de reproduction confirme les tailles de Reconyx en reconstruisant la référence v14 et les trois modes.

Les sources et mesures complètes se trouvent dans `research-results/2026-10-04/jpeg-boundary`. Les logs historiques nomment BASELINE le portefeuille avant v14 ; les colonnes `previous_v14_bytes` et `gain_bytes` des rapports sont la comparaison avec le meilleur résultat effectivement retenu à la version 14.

La compression automatique conserve son portefeuille précédent. Le décodeur expérimental intégré (moteur 24, JV87 / C85) accepte maintenant les modes 2, 3 et 4 et continue à décoder les modes 0 et 1. Un décodeur v14 ne prend pas en charge ces nouveaux modes. La prise en charge reste limitée aux JPEG baseline Huffman 8 bits acceptés par le parseur existant ; il n'y a pas de promesse générale pour les progressifs ou multiscans.

## Reproduire un fichier

Java 17, depuis la racine du projet :

```sh
bash build.sh
java -Xmx2g -cp target/classes io.ivo.jpeg.JpegBoundaryMeasure original.jpg resultats
java -Xmx1g -cp target/classes io.ivo.cli.IvoCli decompress resultats/boundary-m3.ivo restaure.jpg
```

Le programme recalcule la référence v14 (portefeuille historique plus les six essais du palier précédent), écrit `previous-v14-best.ivo`, puis les deux nouveaux candidats et le témoin. Il indique pour chacun le gain par rapport à cette référence. Le build produit ses fichiers compilés localement ; le paquet distribué ne contient aucun `.jar`, `.py` ou `.class`.

## Suite prometteuse

Ce résultat rend utile une extension de la prédiction de frontière aux catégories et amplitudes AC, puis une validation sur davantage de JPEG indépendants avant promotion automatique. Cette extension n'est pas encore implémentée. Les gains observés ici concernent le codage des signes uniquement.

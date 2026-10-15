# JPEG : amplitudes AC prédites aux frontières — version 16

Ce palier étend le prédicteur de frontière de la version 15 aux probabilités des bits inférieurs des amplitudes AC. Il conserve les coefficients quantifiés, les métadonnées et le patch exact du flux JPEG. Le codage des signes est celui du mode 3 de v15 ; les catégories, supports et DC restent ceux du backend qualité. La reconstruction vise le JPEG complet, octet pour octet.

## Résultats sur le corpus initial

Les mêmes 16 JPEG produisent 32 nouveaux candidats (modes 5 et 6). Sept fichiers gagnent ; neuf conservent leur référence v15. Le total retenu passe de **5 210 570 à 5 200 877 octets**, soit **9 693 octets supplémentaires économisés (0,186 %)**. Aucun cas synthétique ne gagne davantage que v15.

| Fichier amélioré | Meilleur v15 | Nouveau candidat | Gain | Mode |
|---|---:|---:|---:|---:|
| 01-25060.jpg | 169955 | 169926 | 29 | 5 |
| 01-25066.jpg | 94356 | 94329 | 27 | 5 |
| Reconyx_HC500_Hyperfire.jpg | 366697 | 366166 | 531 | 5 |
| hdr_canon_hdr_NO.jpg | 665479 | 664401 | 1078 | 5 |
| hdr_iphone_hdr_NO.jpg | 1578126 | 1575113 | 3013 | 5 |
| hdr_iphone_hdr_YES.jpg | 1597608 | 1594612 | 2996 | 5 |
| mobile_jolla.jpg | 651569 | 649550 | 2019 | 5 |

## Validation supplémentaire, modèles figés

Quatorze JPEG supplémentaires du même dépôt public exif-samples ont été choisis sans changer les deux modèles : dix fichiers de la racine non déjà testés et les deux plus gros fichiers distincts de chacun des dossiers mobile et gps. Les empreintes SHA-256 excluent les doublons du corpus initial. Ce corpus complémentaire n'est pas un échantillon aléatoire ni une nouvelle distribution indépendante.

Chaque référence v15 est recalculée au coût IVO complet : portefeuille historique, six modèles voisins de v14, deux modèles de signes de frontière et leur témoin par bande. Les 28 nouveaux candidats se reconstruisent exactement. Cinq fichiers gagnent, neuf conservent v15. Le volume retenu passe de **6 396 272 à 6 389 082 octets**, soit **7 190 octets économisés (0,112 %)**.

| Fichier amélioré | Meilleur v15 | Nouveau candidat | Gain | Mode |
|---|---:|---:|---:|---:|
| Canon_PowerShot_S40.jpg | 31768 | 31740 | 28 | 5 |
| gps_DSCN0010.jpg | 142293 | 142224 | 69 | 5 |
| gps_DSCN0012.jpg | 143797 | 143724 | 73 | 5 |
| mobile_HMD_Nokia_8.3_5G.jpg | 1792138 | 1789947 | 2191 | 5 |
| mobile_HMD_Nokia_8.3_5G_hdr.jpg | 4229822 | 4224993 | 4829 | 5 |

Au total, 30 fichiers et 60 nouveaux candidats : **12 fichiers améliorés et 16 883 octets supplémentaires économisés**. Avec sélection du meilleur candidat ou de v15, le volume passe de **11 606 842 à 11 589 959 octets**, soit 0,145 %. Ces pourcentages sont des réductions pondérées des archives sur les corpus indiqués, après sélection, sans extrapolation à tous les JPEG. Le gain reste bien inférieur à celui du précédent palier sur les signes.

## Modèle et causalité

Le prédicteur de frontière reste celui de `BoundarySigns` : projection DCT partielle avec les tables de quantification originales et les coefficients déjà reconstruits. Le parcours est par fréquence zigzag puis par blocs en serpentin. Les DC sont tous déjà décodés ; les signes AC des fréquences supérieures ne sont pas consultés.

Le mode 5 ramène l'amplitude absolue prédite dans l'intervalle de la catégorie du coefficient courant, déjà décodée. Le mode 6 conserve l'amplitude absolue brute prédite. Pour chaque bit inférieur, le contexte comprend le bit prédit, l'accord entre le préfixe déjà reconstruit et le préfixe prédit, l'accord de catégorie avant ajustement et la bande fréquentielle. Une mixture adaptative entière combine ce contexte avec le modèle de repli catégorie/position du bit. Les paramètres sont réinitialisés par composante.

Les signes et les bits d'amplitude sont stockés dans deux flux distincts. Leur décompression est maintenant coordonnée coefficient par coefficient : calculer la prédiction, décoder l'amplitude, décoder le signe, puis rendre le coefficient disponible aux voisins suivants. Utiliser tous les signes seulement après les amplitudes introduirait une dépendance circulaire ; le nouveau décodeur évite cette dépendance. L'encodeur construit explicitement un tableau causal de coefficients connus. Aucune valeur n'est soustraite ni arrondie dans le fichier reconstruit.

## Validation

**106 archives** sont décompressées dans des processus CLI distincts et comparées octet pour octet : les 60 nouveaux candidats, 32 candidats de frontière v15 et les 14 références recalculées sur le corpus complémentaire. Deux nouvelles archives tronquées sont rejetées. Le build complet passe, puis les dernières sources modifiées uniquement dans leurs commentaires/messages sont compilées sous Java 17.

Le test `BoundaryValuesTest` couvre 72 cas, 18 136 coefficients non nuls : géométries 1/2/5 blocs par 1/2/3 blocs, zéros, amplitudes maximales signées, valeurs aléatoires, quantification jusqu'à 65 535, deux modes et deux entrées invalides. Les tests précédents restent actifs. Les mesures, empreintes, logs et validations sont dans `research-results/2026-10-04/jpeg-boundary-amplitudes`.

Les logs initiaux nomment BASELINE le portefeuille historique avant v14 ; les colonnes `previous_v15_bytes` et les rapports utilisent bien le meilleur résultat retenu à v15. Les logs complémentaires affichent directement BASELINE_V15. Les temps comprennent des reconstructions et ne sont pas un benchmark de démarrage à froid.

## Utilisation expérimentale

Java 17, depuis la racine :

```sh
bash build.sh
java -Xmx2g -cp target/classes io.ivo.jpeg.JpegAmplitudeMeasure original.jpg resultats
java -Xmx1g -cp target/classes io.ivo.cli.IvoCli decompress resultats/amplitude-m5.ivo restaure.jpg
```

Le programme recalcule la référence v15, écrit `previous-v15-best.ivo`, puis `amplitude-m5.ivo` et `amplitude-m6.ivo`. Cette comparaison complète demande plusieurs encodages ; la variante la plus récente peut perdre et doit alors céder la place à la référence.

Le décodeur expérimental intégré (moteur 24, JV87 / C85) accepte les modes 5 et 6, en conservant la lecture des modes précédents. Un décodeur v15 ne lit pas ces nouveaux modes. La compression automatique n'est pas promue. Le support reste limité aux JPEG baseline Huffman 8 bits acceptés par le parseur, sans promesse générale sur les progressifs ou multiscans.

Le paquet distribué ne contient aucun `.jar`, `.py` ni `.class`. Le build peut créer ses fichiers compilés localement.

## Suite

Les signes bénéficient davantage de la continuité des frontières que leurs bits d'amplitude restants. La prochaine hypothèse distincte est de conditionner les catégories et la présence des coefficients sur la prédiction physique et sa confiance. Elle n'est pas implémentée dans ce palier et devra garder les comparaisons au coût final et la restitution exacte.

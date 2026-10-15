# JPEG progressifs et multiscans — version 20

## Résultat

Les cinq JPEG externes auparavant exclus du moteur à coefficients — quatre progressifs et un séquentiel à trois scans — gagnent tous. Le portefeuille expérimental conserve le meilleur des deux modes : **1 997 813 octets IVO**, contre **2 067 803** avec le CLI automatique de version 19, soit **69 990 octets (3,385 %)** supplémentaires.

| Fichier externe | Ancien CLI IVO | Nouveau candidat IVO | Gain |
| --- | ---: | ---: | ---: |
| libjxl flower progressif | 522 052 | 511 113 | 10 939 |
| PyTorch dog1 | 97 483 | 93 744 | 3 739 |
| PyTorch dog2 | 90 857 | 87 150 | 3 707 |
| PyTorch leaning tower | 1 307 295 | 1 258 803 | 48 492 |
| libjxl flower non interleaved | 50 116 | 47 003 | 3 113 |

Les sept dérivés supplémentaires servent à couvrir des paramètres et les reprises : ils ne constituent pas sept nouvelles scènes indépendantes. Leur gain cumulé est 103 800 octets. Le contrôle séquentiel entrelacé conserve le moteur automatique, plus petit de 225 octets : cette nouvelle représentation n’est pas destinée à remplacer le modèle de frontière des JPEG ordinaires.

## Représentation exacte

ProgressiveScanCodec lit le SOF, les tables Huffman successives, chaque SOS, les composantes et leur géométrie. Les scans DC initiaux, DC de raffinement, AC initiaux et AC de raffinement sont parcourus avec les EOBRUN, les coefficients déjà présents et les reprises. Les scans non entrelacés utilisent les dimensions réelles de chaque composante ; les scans entrelacés conservent les blocs de bord associés aux MCU. Les prédicteurs DC et EOBRUN repartent au bon marqueur de reprise.

Le format JPS1 conserve les symboles Huffman originaux, leurs bits d’amplitude/signaux EOB, les corrections des coefficients déjà présents, les bits DC de raffinement, les bits de remplissage et les marqueurs de reprise. Le décodeur reconstitue les codes Huffman, l’ordre exact des bits et le byte stuffing. Aucun réencodage de pixels ni patch contenant le scan original n’intervient. Métadonnées, disposition, 41 cordes et conteneur IVO complet sont comptés.

Mode 0 : contextes par phase DC/AC initial/AC raffinement. Mode 1 : mêmes phases, séparées aussi selon quatre bandes de fréquence (DC, 1..5, 6..20, 21..63). Les bits d’amplitude et de correction sont compactés, puis codés avec le même portefeuille exact que les autres cordes. Les paramètres sont fixés avant les essais ; le meilleur mode est transmis dans le payload.

## Vérifications et domaine

Treize JPEG, deux variantes chacun : 26 archives vérifiées dans de nouveaux processus du CLI, puis comparées par SHA-256 aux originaux. Les treize fichiers passent aussi un oracle ImageIO indépendant : les coefficients Java sont remis dans une syntaxe JPEG séquentielle simple, puis les pixels décodés sont comparés à ceux du JPEG source décodé par ImageIO. Cette vérification de pixels ne prouve pas toutes les combinaisons du standard.

Le test inclut dimensions impaires, sous-échantillonnage, raffinement, séquentiel multiscans, reprises entrelacées et non entrelacées, octets après EOI, et 20 rejets d’entrées malformées ou hors domaine. Les fichiers publics et les recettes des dérivés sont recensés dans `research-results/2026-10-04/jpeg-scans`.

Le domaine est le JPEG DCT Huffman huit bits SOF0/SOF1/SOF2. La précision douze bits, les variantes arithmétiques, DNL et les cadres multiples ne sont pas couverts. La reprise exige la séquence FF Dn canonique ; les octets de remplissage supplémentaires avant une reprise sont refusés. Les nouvelles images couvrent un ensemble limité de scènes, dont des variantes flower corrélées. Le gain de 3,385 % concerne ces cinq fichiers précis.

Le moteur 25 est enregistré pour la décompression uniquement. La compression automatique reste au portefeuille validé en version 19 ; aucune promotion du nouveau lecteur n’est faite ici.

## Reproduire

```sh
bash build.sh
java -Xmx2g -cp target/classes io.ivo.jpeg.ProgressiveScanMeasure essais source.jpg autre.jpg
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli decompress essais/source.jpg/scan-m1.ivo restauree.jpg
java -Xmx1g -cp target/classes:target/test-classes io.ivo.jpeg.ProgressiveScanCodecTest source.jpg
java -cp target/classes io.ivo.jpeg.GenerateScanFixtures source.jpg fixtures
```

Référence technique consultée : la syntaxe du raffinement et EOBRUN dans [libjpeg-turbo jdphuff.c](https://skia.googlesource.com/external/github.com/libjpeg-turbo/libjpeg-turbo/+/refs/heads/3.0.x/jdphuff.c). Le code Java du projet constitue le lecteur et le réémetteur ; aucun programme tiers n’est requis pour produire ou décompresser JPS1. Les grands candidats sont exclus du ZIP ; tailles, empreintes et validations sont conservées.

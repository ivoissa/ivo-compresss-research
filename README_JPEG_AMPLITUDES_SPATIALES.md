# JPEG : amplitudes AC et voisins causaux — palier du 4 octobre 2026

Le test encode les bits inférieurs des amplitudes AC à partir des coefficients déjà reconstruits dans les blocs voisins. Il conserve les coefficients quantifiés, les métadonnées et le patch de restitution du flux entropique original. La reconstruction vise le fichier JPEG complet, octet pour octet.

Deux modes de contexte sont comparés : bits des voisins horizontal et supérieur ; puis ajout de la bande fréquentielle et de l'accord de catégorie. Un mélange adaptatif entier combine le modèle spatial avec un modèle de repli catégorie/position du bit. Le parcours serpentin est causal, avec un modèle réinitialisé par composante. Les catégories, supports, DC et signes restent ceux du backend existant.

Les deux modes sont essayés avec trois backends : spatial/spectral (0), directionnel (1), similarité/qualité (2). Le témoin reste le portefeuille précédent incluant STORE et les moteurs génériques. Les tailles sont celles des archives IVO complètes, avec le même nom original.

## Résultats

96 candidats sur 16 JPEG : 10 exemples publics, 2 captures utilisateur et 4 petits cas synthétiques. Tous les candidats sont reconstruits exactement dans des processus CLI distincts et vérifiés par SHA-256. Trois archives tronquées, une par backend, sont rejetées. Le build complet passe ; le test unitaire couvre 72 cas, 30 228 amplitudes, 50 000 décisions binaires avec changement de distribution et trois entrées invalides.

Huit fichiers améliorés : sept non synthétiques et un synthétique. Gain cumulé avec sélection du meilleur candidat : **1 046 octets**, dont 1 034 sur les douze fichiers non synthétiques. Les huit autres fichiers conservent le résultat précédent. Ce petit corpus ne permet pas de conclure à un gain représentatif ; les mesures ne modifient pas une moyenne calculée sur un autre corpus.

| Fichier | IVO précédent | Meilleur essai | Gain en octets |
|---|---:|---:|---:|
| Reconyx_HC500_Hyperfire.jpg | 379 231 | 378 957 | 274 |
| hdr_iphone_hdr_NO.jpg | 1 652 929 | 1 652 706 | 223 |
| hdr_iphone_hdr_YES.jpg | 1 671 157 | 1 670 996 | 161 |
| hdr_canon_hdr_NO.jpg | 675 759 | 675 615 | 144 |
| mobile_jolla.jpg | 681 254 | 681 165 | 89 |
| 01-25066.jpg | 94 774 | 94 696 | 78 |
| 01-25060.jpg | 171 468 | 171 403 | 65 |
| fixture-2.jpg | 950 | 938 | 12 |

Les sept gains non synthétiques utilisent le backend 2, mode 1 ; le cas synthétique utilise le backend 0, mode 1. Les données complètes, manifestes et validations sont dans `research-results/2026-10-04/jpeg-neighbour`. Les temps comprennent le travail de mesure et ne constituent pas un benchmark fiable de démarrage à froid.

## Utilisation expérimentale

Java 17, depuis la racine :

```sh
bash build.sh
java -Xmx2g -cp target/classes io.ivo.jpeg.JpegNeighbourMeasure original.jpg resultats 0,1,2
java -Xmx1g -cp target/classes io.ivo.cli.IvoCli decompress resultats/neighbour-b2-m1.ivo restaure.jpg
```

Le build crée localement ses fichiers compilés ; ils ne sont pas distribués dans le paquet source. Les fixtures peuvent être recréées à partir de `research-experiments/jpeg-neighbour/src/io/ivo/jpeg/GenerateNeighbourFixtures.java`.

Le décodeur expérimental utilise l'identifiant de moteur 24 et les signatures JV85/JV86/JV87, avec le format de coefficients C85 et le numéro de mode. Il est enregistré pour la décompression uniquement ; la compression automatique conserve son portefeuille précédent. La prise en charge est limitée aux JPEG baseline Huffman 8 bits acceptés par les parseurs existants, sans promesse universelle pour les JPEG progressifs ou multiscans.

## Piste suivante

Le simple contexte de bits voisins semble proche de son plafond sur ce corpus. Une piste plus différente est la prédiction des coefficients depuis la continuité aux frontières des blocs, ou depuis l'énergie des autres fréquences. Elle devra d'abord être mesurée séparément, puis comparée aux tailles IVO complètes avec les mêmes contrôles d'exactitude. Elle n'est pas implémentée ici et aucun gain n'est garanti.

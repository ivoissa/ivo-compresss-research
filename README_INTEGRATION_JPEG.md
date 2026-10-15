# JPEG automatique — version 19

Le CLI standard compare désormais les modèles JPEG de frontière causale (modes 9 et 10) aux moteurs historiques. Il conserve le plus petit conteneur IVO complet, après vérification de la reconstruction exacte. Les paramètres des modèles validés précédemment restent identiques.

```sh
bash build.sh
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli compress photo.jpg photo.ivo
java -Xmx1g -cp target/classes io.ivo.cli.IvoCli decompress photo.ivo restauree.jpg
```

## Validation

Les 56 JPEG des corpus structure et externe ont été compressés par le CLI standard, puis décompressés dans un processus distinct : tous sont identiques octet pour octet aux originaux. Les tailles reproduisent les meilleurs résultats publiés : 35 fichiers sélectionnent le nouveau portefeuille, 21 conservent un moteur historique. Le total des archives est de 17 453 453 octets. L’intégration rend accessibles les gains de recherche ; elle n’ajoute pas un gain supplémentaire aux mesures précédentes.

Onze archives historiques compatibles ont également été relues avec contrôle SHA-256 : variantes JPEG antérieures et MP39/MP40/MP41. Les preuves figurent dans `research-results/2026-10-04/jpeg-integration`. Une ancienne archive de recherche MP31 déjà illisible avec l’ancien CLI n’est pas incluse dans cette validation ; le code MP3 n’a pas changé.

## Compatibilité et coût

L’identifiant de format 24 et la lecture des variantes JPEG JV85/JV86/JV87 sont conservés. Le nom interne `JPEG_NEIGHBOUR_RESEARCH` est historique : ce moteur participe maintenant à la compression automatique.

Le nouveau modèle vise les JPEG Huffman 8 bits pris en charge par le parseur. Les JPEG progressifs, arithmétiques, 12 bits ou multiscan non pris en charge restent traités par les autres moteurs, avec STORE disponible. Les deux encodages supplémentaires augmentent le coût CPU et mémoire ; les grandes images du corpus ont été vérifiées avec `-Xmx2g`. Aucun gain nouveau pour MP3 ou MP4 n’est revendiqué.

Dans `IvoCli.main`, les arguments initialisent les variables `command`, `inputPath` et `outputPath`, utilisées ensuite par les appels.

L’archive distribuée contient les sources et résultats, sans `.jar`, `.py` ni `.class`. La compilation crée ses binaires localement. Les documents des versions précédentes décrivent leur état historique.

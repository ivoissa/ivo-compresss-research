Mise à jour du 8 octobre 2026 : `compress` sélectionne désormais automatiquement les candidats de recherche validés. Voir [README_JPEG_AUTO_2026_10_08.md](README_JPEG_AUTO_2026_10_08.md) pour les mesures sur la photo Fold5 et les modes explicites.

Mise à jour du 6 octobre 2026 : audit de dix classes et moteur expérimental MP4 + AAC 22,05/24 kHz en mode `compress-enhanced`. Voir [README_CODEC_AUDIT_2026_10_06.md](README_CODEC_AUDIT_2026_10_06.md).

# Stockage compact optionnel — version 29

`compress-compact`, `bench-compact` et `decompress-compact` réduisent les tables rapides du mélangeur sans changer les probabilités ni le format. Les 32 bits restent par défaut : la réduction des allocations ne garantit pas une accélération. Voir [README_COMPACT_MIXER.md](README_COMPACT_MIXER.md).

# Nouveauté version 28

`compress-fast` / `bench-fast` ajoutent une sélection plus rapide avec contrôle exact. Le décompresseur reste commun. Le mode rapide peut perdre du taux sur des entrées inconnues. Voir [README_PERFORMANCE_FAST.md](README_PERFORMANCE_FAST.md).

# Nouveauté version 27

`java -Xmx1g -cp target/classes io.ivo.cli.IvoCli compress-enhanced progressive.jpg progressive.ivo` compare aussi les nouveaux modèles progressifs/multiscans aux références. Voir [README_JPEG_PROGRESSIVE_MIXER.md](README_JPEG_PROGRESSIVE_MIXER.md).

# Nouveauté version 26

Pour comparer aussi les nouveaux mélanges JPEG : `java -Xmx1g -cp target/classes io.ivo.cli.IvoCli compress-enhanced image.jpg image.ivo`. Voir [README_JPEG_CONTEXT_MIXER.md](README_JPEG_CONTEXT_MIXER.md) pour les résultats et la reproduction.

# Nouveauté version 25

Après `bash build.sh`, lancer `java -Xmx2g -cp target/classes io.ivo.cli.IvoCli compress-enhanced fichier.mp4 fichier.ivo`. Cela compare les moteurs utilisateur et habituels, avec contrôle exact et choix du plus petit conteneur. Décompresser par `java -Xmx2g -cp target/classes io.ivo.cli.IvoCli decompress fichier.ivo fichier-restaure.mp4`. Voir [README_USER_CODE_INTEGRATION.md](README_USER_CODE_INTEGRATION.md).

# IVO — projet Java complet, 4 octobre 2026

## Démarrage simple

Ouvrir **ce dossier**, celui qui contient `pom.xml`, dans IntelliJ IDEA. Il contient `src/main/java`, `src/test/java`, tous les moteurs intégrés et les sources des expériences. Java 17 requis. Aucun JAR précompilé ni fichier Python exécutable n’est distribué ; les scripts de recherche Python sont conservés en `.py.txt`.

Ce paquet rassemble les versions disponibles et mesurées. « Dernière version » ne signifie pas qu’un moteur récent gagne sur tous les fichiers. Le portefeuille doit conserver les anciens candidats lorsqu’ils sont plus petits.

| Fichiers | Traitement intégré | État / guide |
|---|---|---|
| TXT, CSV, binaires | N&C contextuel, REF + N&C, STORE | Routeur général |
| JSON | Tokens JSON exacts + moteurs génériques | `src/main/java/io/ivo/json` |
| DEFLATE / zlib / gzip | Parseur/réémetteur exact + cordes | `README_DEFLATE.md` |
| ZIP, Word DOCX, Excel XLSX | Moteurs ZIP/DEFLATE, structures et métadonnées exactes | `README_DEFLATE.md`, `docs` ; pas de moteur métier Excel spécialisé |
| Word DOC, Excel XLS | Moteurs génériques et STORE | Pas de moteur spécialisé des anciens formats binaires |
| PDF | PDF V2, flux DEFLATE exacts selon le parseur | `src/main/java/io/ivo/pdf` |
| JPEG | Portefeuille historique + frontières causales (modes 9/10), concours au coût final | `README_INTEGRATION_JPEG.md`, `README.md` ; prise en charge spécialisée limitée aux JPEG acceptés par les parseurs |
| MP3 intégré | MP31 + MP39 + MP40, concours au coût final | `README_INTEGRATION_MP3.md` |
| MP3, recherche optimisée | Portefeuille MP33 à MP37, parseur MP38, cordes MP39 et contextes MP40 | `README_MP3_CORDES.md` ; commandes dédiées ci-dessous |
| MP4 | Candidats structurels V1, V2, V3 | Sources dans `src/main/java/io/ivo/mp4` ; optimisation vidéo non achevée |

DOCX et XLSX sont traités comme des conteneurs ZIP. Ce paquet ne prétend pas avoir mesuré une optimisation spécifique des cellules Excel. Le fichier est traité comme des octets : la reconstruction vise le fichier complet, pas seulement son contenu affiché.

### Compiler et utiliser le routeur intégré

```bash
mvn test package
java -jar target/ivo-0.8.0-research.jar compress original.json original.ivo
java -jar target/ivo-0.8.0-research.jar decompress original.ivo original-restaure.json
```

Les mêmes commandes s’appliquent à `.txt`, `.docx`, `.xlsx`, `.jpg`, etc. Pour comparer les moteurs et afficher leurs coûts :

```bash
java -jar target/ivo-0.8.0-research.jar bench original.jpg
java -jar target/ivo-0.8.0-research.jar bench-corpus dossier-tests
```

Alternative sans Maven : `bash build.sh`. Les scripts `.sh` sont utilisables dans le terminal Linux du Chromebook.

### Tester le dernier MP3 : MP39

MP39 n’est pas encore enregistré dans le routeur intégré. Un simple `compress original.mp3` appelle donc le portefeuille intégré antérieur, et pas MP39. Pour compiler les expériences et leurs tests :

```bash
bash build-reservoir.sh
java -Xmx2048m -cp target/classes:target/reservoir-classes io.ivo.mp3.CordsMeasure original.mp3 resultats-mp39 0,1,2,3
```

Cette commande produit quatre archives et vérifie chaque reconstruction exacte. Elle conserve un cache de métadonnées vérifié par SHA-256. Comparer les quatre tailles réellement écrites et garder aussi les anciens candidats lorsqu’ils gagnent.

```bash
java -Xmx2048m -cp target/classes:target/reservoir-classes io.ivo.mp3.CordsMeasure decode resultats-mp39/spectral-s2.ivo original-restaure.mp3 travail
```

Le décodeur final travaille en mémoire et ne requiert pas le MP3 original. `travail` est un argument conservé pour compatibilité, sans écrire de cordes temporaires. Sur Windows, remplacer le séparateur `:` du classpath par `;`.

Pour recalculer le portefeuille MP33 à MP37 sur un petit fichier :

```bash
java -Xmx2048m -cp target/classes:target/reservoir-classes io.ivo.mp3.PreviousVectorBenchmark original.mp3 precedent.ivo
```

Sur le gros MP3, ce recalcul exhaustif antérieur n’est pas une procédure de production validée en mémoire bornée. Les archives expérimentales MP37 nécessitent le décodeur de recherche correspondant ; ne pas les confondre avec le format MP31 intégré. Les instructions détaillées restent dans `README_MAIN_LANES.md` et les sources.

### Derniers résultats MP3 validés

13 fichiers, quatre regroupements, **52 archives rouvertes dans un processus distinct et reconstruites octet pour octet**. MP39 améliore le meilleur précédent sur 6 fichiers et perd sur 7. Les anciennes références de même SHA-256 et les coûts complets sont conservés dans les rapports.

Le grand fichier de 95 634 889 octets donne 83 508 548 octets avec MP39, contre 87 297 487 avec MP37 : 3 788 939 octets supplémentaires gagnés, soit 4,34 % du précédent conteneur. Le gain total par rapport à l’original est 12,68 %. Cela reste une mesure de ce corpus, pas une promesse de gain sur tout MP3.

### Mise à jour MP40

Lire `README_MP3_CONTEXTES.md` pour les commandes du nouveau candidat, ses limites et les mesures. Le meilleur grand cas passe de 83 508 548 à 82 851 262 octets. MP40 améliore quatre des treize fichiers, conserve les anciens candidats pour les autres et reste une expérience séparée du routeur intégré. La décompression MP39/MP40 utilise `CordsMeasure`.

## Partie technique

Le routeur intégré essaie uniquement les moteurs dont `supports` accepte le fichier. Pour chaque candidat, il vérifie `Arrays.equals(original, engine.decompress(payload))`, construit le conteneur IVO1 complet puis minimise sa taille :

`candidat retenu = argmin taille(IVO1(moteur, longueur originale, nom, SHA-256, payload))`

Le payload seul ne suffit pas au classement. STORE participe à la comparaison. La décompression vérifie également la longueur originale et le SHA-256. Une extension de fichier n’est pas une preuve que le parseur spécialisé accepte tous ses variants.

Architecture des sources :

- `src/main/java/io/ivo/engine` : interfaces, moteurs génériques et routeur.
- `src/main/java/io/ivo/container` : conteneur et encadrement.
- `src/main/java/io/ivo/nc`, `ref` : modèles contextuels, arithmétique et références.
- `src/main/java/io/ivo/json`, `deflate`, `zip`, `pdf`, `jpeg`, `mp3`, `mp4` : moteurs intégrés.
- `research-experiments/mp3-reservoir/src/io/ivo/mp3` : MP33–MP39 et tests spécialisés.
- `research-results` : tailles, hashes et traces de validation ; certaines expériences historiques restent moins bonnes et ne sont pas retenues par défaut.

JPEG conserve les décisions nécessaires à la réémission exacte du flux. MP39 conserve les tables et décisions MP3, les facteurs d’échelle, présence, magnitude, signe, nombres de quadruplets et bits résiduels. Il ne réencode pas du PCM. Les formules, schémas et limites exactes sont documentés dans les README spécialisés.

Les moteurs de recherche sont compilés séparément par `build-reservoir.sh` ; le `pom.xml` compile le projet intégré et ses tests. La séparation est explicite : ce paquet contient bien le code MP39 complet, mais ne prétend pas que le CLI intégré l’utilise déjà.

## Suite de la recherche

Piste MP3 : conditionner le codage des amplitudes sur des informations déjà conservées et connues du décodeur : `global_gain`, facteurs d’échelle, canal, position et type de bloc. Comparer ce conditionnement aux cordes MP39 actuelles, sans transmettre inutilement les mêmes informations. Ne retenir un contexte ou une transformation que si le conteneur complet gagne et si la reconstruction reste exacte. Cette première partition a maintenant été mesurée : voir `README_MP3_CONTEXTES.md`. Les facteurs effectifs par bande et les classes de gain plus fines restent à explorer.

## Jalon CABAC version 12

Voir README_CABAC_MOUVEMENTS.md : références et différences de mouvement du premier macrobloc inter non sauté, comparaison directe FFmpeg. Aucun nouveau gain de compression annoncé.

## MP3 expérimental version 13

Voir README_MP3_TEMPOREL.md : échelles par bande et prédiction temporelle, mesures complètes et limites. MP40 reste le meilleur sur Tao ; MP41 est décompressable par le CLI mais hors sélection automatique.

## Palier JPEG — version 14

Voir `README_JPEG_AMPLITUDES_SPATIALES.md` : 96 essais exacts, huit fichiers améliorés et 1 046 octets gagnés au total. Décodeur expérimental intégré ; compression automatique inchangée. Paquet source sans `.jar`, `.py` ni `.class`.

## Palier JPEG — version 15

Voir `README_JPEG_FRONTIERES.md` : prédiction causale des signes AC aux frontières des blocs ; 201 748 octets gagnés sur le corpus par rapport à v14. 48 nouveaux essais et 24 régressions reconstruits exactement. Reproduction via `JpegBoundaryMeasure`. Compression automatique non promue.

## Palier JPEG — version 16

Voir `README_JPEG_FRONTIERES_AMPLITUDES.md` : deux modèles causaux des bits d’amplitude, 60 essais sur 30 JPEG, 16 883 octets supplémentaires économisés / v15. 106 archives vérifiées exactement. Reproduction via `JpegAmplitudeMeasure`. Aucun changement du portefeuille automatique.

## Palier JPEG — version 17

Voir `README_JPEG_FRONTIERES_STRUCTURE.md` : catégories et présence AC causales, quatre variantes dont deux témoins ; 267 191 octets supplémentaires gagnés / v16 sur 30 JPEG. 150 archives vérifiées exactement. Reproduction via `JpegStructureMeasure`. Compression automatique non promue.

## Validation JPEG externe — version 18

Voir `README_JPEG_VALIDATION_EXTERNE.md` : modèles v17 inchangés, 26 JPEG de cinq dépôts, 19 compatibles tous améliorés et sept replis exacts. 94 723 octets gagnés / v16 sur les compatibles ; 102 archives vérifiées. Reproduction via `JpegExternalMeasure`.

## Version 19 : intégration JPEG automatique

Le CLI standard utilise maintenant le portefeuille de frontière validé, en concurrence avec les moteurs historiques. Voir [README_INTEGRATION_JPEG.md](README_INTEGRATION_JPEG.md). Les 56 JPEG testés ont une restitution exacte et retrouvent les tailles publiées.

## Version 20 : JPEG multiscans, MP3 énergie, MP4 corps vidéo

Voir [README_RECHERCHE_PALIER_20.md](README_RECHERCHE_PALIER_20.md) pour les trois essais et leurs résultats. Les programmes de mesure produisent les nouvelles archives ; le CLI habituel les décompresse. Le portefeuille automatique de version 19 est conservé.

## Version 21 : validation sur de nouvelles sources

Voir [README_VALIDATION_PALIER_21.md](README_VALIDATION_PALIER_21.md). Les modèles sont inchangés ; les mesures incluent les meilleurs précédents et les restitutions exactes. EnergyExternalMeasure permet de reproduire toute la comparaison MP3.

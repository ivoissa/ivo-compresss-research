# IVO — reprise DeflateIrEngine, 3 octobre 2026

## Explication simple

IVO essaie plusieurs façons de ranger les données. Il garde celle qui produit le plus petit fichier `.ivo` complet, après avoir vérifié que la décompression restitue exactement l'entrée. STORE conserve les données telles quelles lorsque les autres essais ne font pas mieux ; le conteneur ajoute néanmoins quelques octets.

DEFLATE est utilisé notamment dans gzip, ZIP et PNG. Le moteur ne décompresse pas puis ne recompresse pas avec `Deflater` : il mémorise les décisions du flux existant et les réécrit. Les informations sont séparées en cinq cordes, afin de compresser ensemble des éléments de même nature.

Cette reprise consolide le moteur existant du projet 1.5.0. Les anciens moteurs spécialisés sont conservés, mais leurs noms et anciens documents ne constituent pas une validation de leur maturité. Aucun nouveau branchement ZIP/DOCX/PNG/PDF ni travail JPEG/MP3/MP4 n'est effectué dans cette étape.

## Construire et utiliser

Java 17, sans dépendance externe pour compiler et exécuter :

```sh
bash build.sh
java -jar target/ivo-0.7.0-SNAPSHOT.jar compress mon-fichier mon-fichier.ivo
java -jar target/ivo-0.7.0-SNAPSHOT.jar decompress mon-fichier.ivo fichier-restaure
java -jar target/ivo-0.7.0-SNAPSHOT.jar bench mon-flux.deflate
java -jar target/ivo-0.7.0-SNAPSHOT.jar deflate-check dossier-de-flux
```

Le ZIP livré contient `pom.xml` et `src/`. `mvn test` est configuré pour exécuter les deux programmes de tests ; Maven n'est pas disponible dans l'environnement de validation, donc seule la voie `build.sh` a été exécutée. Les JAR sont produits localement et ne sont pas inclus dans le livrable source.

## Structures et invariants techniques

Référence : https://www.rfc-editor.org/rfc/rfc1951

- META : type/finalité de chaque bloc, bits exacts des en-têtes Huffman dynamiques, alignement des blocs stockés, LEN/NLEN, bourrage final, octets après le flux.
- RUN : nombre de littéraux avant chaque match et après le dernier.
- LITERAL : octets littéraux, y compris données des blocs stockés.
- LENGTH et DISTANCE : entiers des matches, codés en varints.

Pour un bloc de `m` matches : `RUN.length = m + 1`, `LENGTH.length = DISTANCE.length = m`, et `sum(RUN) = LITERAL.length`. Les codes Huffman canoniques sont reconstruits à partir des longueurs ; l'en-tête dynamique original est conservé bit pour bit. Les entiers DEFLATE sont transmis LSB-first, les codes Huffman MSB-first.

Le flux IR est `DIR2 + cinq sections préfixées par leur taille`. Le payload moteur est `DFE2 + type d'enveloppe + préfixe + suffixe + cinq cordes`. Chaque corde choisit STORE, N&C arithmétique contextuel, ou REF suivi de N&C. Le coût de corde est `1 + varint_size(payload.length) + payload.length` : à modes de largeur fixe, minimiser la longueur du payload minimise aussi ce coût. Le routeur compare ensuite les conteneurs IVO complets : en-tête, nom, taille originale, SHA-256, taille payload et payload.

Invariants exécutés : `fromIrBytes(toIrBytes(D)) = D`, `fromSections(toSections(D)) = D`, `decode(encode(X)) = X`. Le routeur exclut tout candidat dont la reconstruction diffère. La décompression du conteneur vérifie taille et SHA-256.

Cette reprise ajoute les contrôles LEN/NLEN, HLIT réservé, finalité et flags IR, bornes des compteurs et tranches, champs des blocs stockés et bourrage. Elle ne constitue pas un audit complet de sécurité des décodeurs.

## Validation réellement exécutée

Voir `validation.log`, `BENCHMARK_CODECS.md`, `benchmark_codecs.csv` et `deflate-measurement.log`.

Le test déterministe utilise la graine 1951 : 60 entrées, quatre niveaux, trois stratégies, vidanges intermédiaires, mutations du bourrage et octets de traîne. Il couvre les trois types de blocs et les flux multiblocs. L'Inflater Java contrôle également le contenu décompressé des flux générés. Les tests de conteneur vérifient reconstruction exacte et minimum du coût final sur un sous-ensemble. Le nombre de reconstructions compte les variantes vérifiées, pas des fichiers réels indépendants.

Benchmark :

```sh
python3 scripts/benchmark-codecs.py
```

Le script compare le routeur IVO à gzip-9, bzip2-9, xz-9, zstd-19 et Brotli-11, et vérifie chaque aller-retour. Python standard, commande `zstd` et bibliothèques système `libbrotlienc`/`libbrotlidec` sont nécessaires. Ajouter des fichiers à `bench-corpus/` pour un corpus réel. Le défaut est trois exécutions par mesure ; `IVO_BENCH_REPEATS` permet de le changer. Les temps IVO incluent le démarrage JVM, zstd le sous-processus, les autres codecs sont mesurés en mémoire : ces temps ne sont pas une comparaison équitable des performances intrinsèques. Les tailles sont celles des sorties complètes ; les formats ne proposent pas tous les mêmes métadonnées ou contrôles d'intégrité.

## Limites et prochain jalon

Les résultats actuels sont un benchmark synthétique reproductible, pas une victoire générale. La compression IVO n'est pas la meilleure sur toutes les entrées. Le fichier complet peut dépasser l'original même en STORE, à cause du conteneur.

Le traitement travaille en mémoire (`byte[]`) ; il n'est pas adapté à des fichiers arbitrairement grands. Les enveloppes gzip/zlib sont conservées ; leurs checksums ne sont pas vérifiés par le parseur IR. Les données de dictionnaire prédéfini ne sont pas nécessaires à la reconstruction syntaxique. Les membres gzip supplémentaires sont conservés en traîne, sans optimisation individuelle. Le parseur rejette la représentation 284 + extra 31 de la longueur 258, hors plage du tableau RFC1951, même si certains décodeurs la tolèrent. Les tests ne couvrent pas encore toutes les combinaisons d'arbres dynamiques fabriqués, dictionnaires et enveloppes optionnelles.

Avant une promotion du moteur : élargir les fixtures indépendantes et le corpus réel ; mesurer le moteur DEFLATE et le routeur séparément ; contrôler les ressources des décodeurs ; puis intégrer successivement ZIP, DOCX, PNG, PDF avec tests octet pour octet des fichiers complets. REF+Delta et routage multiscalaire restent des axes de recherche.

## Originalité et méthodes connues

REF est apparenté à LZ ; le codage arithmétique et les contextes relèvent de méthodes connues, proches des familles PPM. La reconstruction de flux DEFLATE existants est apparentée aux objectifs de Preflate. Séparer les décisions en cordes et choisir leur encodage par coût réel décrit ici l'architecture IVO ; aucune priorité scientifique ni nouveauté mathématique n'est établie par ces tests. Lepton concerne une approche spécialisée JPEG, à traiter séparément.

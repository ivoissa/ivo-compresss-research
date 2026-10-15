# MP39 — cordes spectrales MP3 exactes

## Explication simple

Ce candidat ne convertit pas le MP3 en son. Il lit les décisions de codage déjà présentes dans le fichier et sépare leurs informations : présence des coefficients, amplitudes, signes, facteurs d’échelle, nombre de quadruplets et bits restants. Il compresse ces cordes séparément, puis réécrit les codes MP3 d’origine.

Quatre regroupements sont comparés : toutes les valeurs ensemble, par canal, par canal et position, ou par canal et granule. Le programme essaie plusieurs codeurs par corde et mesure des conteneurs `.ivo` complets. Une nouvelle représentation n’est à retenir que si elle bat les candidats précédents, STORE compris. Les nombres ci-dessous sont des mesures de fichiers effectivement écrits et reconstruits, pas des estimations d’entropie.

MP39 est encore un candidat de recherche. L’identifiant 18 est local et expérimental. Le CLI principal conserve MP31 et ne décode pas ces archives : utiliser `CordsMeasure` fourni. L’intégration au routeur principal reste à faire après un corpus MP3 plus représentatif.

## Résultats mesurés

**13 fichiers, 52 archives exactes contrôlées dans un nouveau processus ; 6 fichiers améliorés, 7 moins bons.** Les valeurs sont en octets. Une valeur négative de gain supplémentaire impose de conserver le candidat précédent.

| Fichier | Original | Ancien meilleur .ivo | MP39 meilleur .ivo | Gain supplémentaire (octets) | Schéma |
|---|---:|---:|---:|---:|---:|
| MEANDR_PHASE0.mp3 | 4 001 280 | 3 875 381 | 3 795 012 | +80 369 | 0 |
| MIPSTest.mp3 | 328 320 | 319 383 | 314 563 | +4 820 | 0 |
| generated-22050-1.mp3 | 16 762 | 8 415 | 8 677 | -262 | 0 |
| generated-22050-2.mp3 | 16 762 | 7 178 | 7 680 | -502 | 0 |
| generated-44100-1.mp3 | 33 062 | 13 493 | 13 474 | +19 | 0 |
| generated-44100-2.mp3 | 33 062 | 12 078 | 12 323 | -245 | 0 |
| generated-8000-1.mp3 | 6 741 | 6 169 | 6 351 | -182 | 0 |
| generated-8000-2.mp3 | 6 741 | 5 188 | 5 692 | -504 | 0 |
| l3-compl-cut.mp3 | 192 | 251 | 432 | -181 | 0 |
| l3-test45.bit | 391 105 | 343 560 | 325 370 | +18 190 | 0 |
| l3-test46.bit | 130 613 | 121 474 | 109 287 | +12 187 | 0 |
| noise_meandr.mp3 | 401 280 | 390 162 | 392 987 | -2 825 | 0 |
| Tao | 95 634 889 | 87 297 487 | 83 508 548 | +3 788 939 | 2 |

Sur Tao, le meilleur candidat mesure 83 508 548 octets : 12,68 % de réduction par rapport au MP3 original et 4,34 % de moins que MP37. Le schéma 2 gagne sur ce grand fichier ; le schéma 0 est le meilleur des quatre sur les 12 petits fichiers. Le gain de 19 octets du signal 44,1 kHz mono est marginal.

Les six anciens portefeuilles des signaux générés ont été recalculés pour cet essai ; les autres références MP37 proviennent des mesures antérieures de même SHA-256, et MP38 est aussi conservé dans la comparaison. Le petit fichier de fuzz revient à STORE dans le portefeuille précédent.

## Partie technique

### Représentation et invariants

`ReservoirTrial.split` conserve préfixe, headers, CRC, side_info, main_data et suffixe. MP39 réutilise les formats de métadonnées de MP37 : aucun dictionnaire externe ni fichier de référence n’est nécessaire à la restitution. Le réservoir reste physique ; `main_data_begin` et `part2_3_length` déterminent les intervalles logiques. Tous les bits en dehors de ces intervalles sont conservés.

Les cordes sont :

| Corde | Contenu exact |
|---|---|
| CONTROL | Nombre de quadruplets count1 complets, VarInt par partie non vide |
| SCALE | Valeurs des facteurs d’échelle effectivement transmis ; les largeurs et les omissions scfsi sont déduites du side_info |
| UNUSED | Bits physiques hors des parties déclarées, empaquetés sans modification |
| RESIDUAL | Bits de fin de partie après le dernier quadruplet complet |
| SUPPORT | Masque de présence d’une paire, `1[x≠0] + 2·1[y≠0]` |
| MAGNITUDE | Pour chaque valeur non nulle, VarInt de `abs(v)-1` |
| SIGN | Un bit pour chaque valeur non nulle : 1 pour négatif |
| QUAD | Masque de présence des quatre valeurs count1, poids 8, 4, 2, 1 |

La reconstruction d’une valeur non nulle est `v = (-1)^s · (u+1)`, où `u` provient de MAGNITUDE et `s` de SIGN. Une valeur absente vaut zéro. La table Huffman zéro implique deux valeurs nulles et ne transmet aucune valeur dans ces trois cordes. Les codes Huffman, les extensions linbits et les signes sont réémis avec les tables imposées par le side_info. Les quadruplets count1 ont des amplitudes implicites égales à un ; QUAD et SIGN suffisent, avec leur nombre exact dans CONTROL.

Le nombre de quadruplets est indispensable pour préserver les quadruplets nuls complets en fin de segment. Le décodeur ne peut pas le remplacer par la position du dernier coefficient non nul. Une séquence incomplète reste dans RESIDUAL. Les parties de longueur nulle avec paires implicites de table zéro sont prises en charge.

Les cordes binaires se terminent par un remplissage nul du dernier octet. Leur nombre de bits utiles est déterminé par le parcours du décodeur ; il refuse un remplissage non nul ou des octets superflus. Aucun arrondi, déquantification, transformation PCM ou conversion ImageIO n’intervient.

Invariants contrôlés : `Arrays.equals(original, decode(encode(original)))`, longueur d’origine, SHA-256 et égalité des comptes spectraux et des empreintes des valeurs. SHA-256 complète le test octet pour octet ; il ne le remplace pas.

### Quatre regroupements

| Schéma | Organisation | Nombre de cordes |
|---|---|---:|
| 0 | Huit familles globales | 8 |
| 1 | Huit familles par canal | 16 |
| 2 | Par canal ; SUPPORT/MAGNITUDE/SIGN/QUAD séparés par plages de position | 40 |
| 3 | Huit familles par granule et canal | 32 |

Pour le schéma 2, les plages sont `[0,16)`, `[16,64)`, `[64,256)` et `[256,576)`, dans l’ordre des valeurs Huffman. Ce sont des positions de syntaxe, pas des bandes psychoacoustiques ni une représentation après réordonnancement des blocs courts. Les positions des paires sont `2i` ; celles des quadruplets, `2·big_values+4j`. Les cordes inutilisées sont explicitement vides. UNUSED reste global.

### Codeurs et coûts réels

Chaque corde dispose d’un octet de mode et d’un VarInt de longueur originale. Les modes sont RAW, zlib niveau 9, N&C ordre 1 avec 257 contextes, REF suivi de ce N&C, et N&C ordre 7 avec 5 000 contextes. REF utilise une correspondance minimale de 8 octets, une fenêtre de 65 535 octets et au plus 4 candidats ; son dictionnaire redémarre à chaque bloc de 256 Kio. Le modèle N&C et l’historique des octets persistent entre ses blocs, chacun ayant son propre flux arithmétique encadré par sa longueur.

Pour limiter le calcul : les grandes cordes binaires de plus de 1 Mio n’essaient que RAW et zlib ; N&C ordre 7 est essayé uniquement jusqu’à 256 Kio ; REF uniquement à partir de 1 024 octets. Un `-1` dans le log signifie candidat non essayé. Ces règles ne démontrent pas que les candidats omis sont inutiles. Le portefeuille est borné, pas exhaustif.

Le choix interne minimise la taille codée effectivement obtenue. Le mode et la longueur originale ont un coût identique entre codeurs d’une même corde ; l’encadrement extérieur par longueur est monotone. Le choix entre regroupements compare ensuite les tailles complètes `.ivo`, incluant le nom du fichier, le SHA-256, le payload et toutes les métadonnées.

Le payload MP39 contient la signature `MP39`, le format side_info, puis sept champs encadrés par VarInt : layout, préfixe, headers, CRC, side_info, cordes spectrales, suffixe. Le champ spectral contient schéma, nombre de cordes, puis chaque corde encadrée par sa taille codée. Les métadonnées sont soit reprises d’une archive précédente de même longueur et SHA-256, soit reconstruites avec le schéma de blocs 2 de MP36/MP37. Ce dernier choix n’est pas une recherche exhaustive des autres schémas de métadonnées ; les chiffres comparent néanmoins des archives complètes.

### Contrôles et limites

Les tests unitaires précédents sur 99 260 paires Huffman et 162 quadruplets passent. Le nouveau test couvre les cinq codeurs sur plusieurs blocs, quatre entrées malformées et 48 reconstructions différentielles de cordes sur 12 fichiers. Les statistiques de chaque candidat sont aussi comparées aux résultats du parseur précédent ; ceux-ci ont été contrôlés par l’oracle indépendant minimp3 sur 12 des 13 fichiers. Le petit fichier de fuzz n’avait pas été envoyé à cet oracle.

Deux passes intermédiaires avec des cordes décodées sur disque ont échoué sur Tao (schémas 2 et 3) ; leurs traces sont conservées et leurs exécutions échouées ne servent pas de preuve. Le décodeur final vérifie la longueur des cordes en mémoire avant la réémission. Toutes les archives ont ensuite été rouvertes et validées avec ce décodeur final dans un processus distinct. La cause précise de la troncature des fichiers temporaires n’a pas été établie.

Les fixtures couvrent MPEG-1, MPEG-2, MPEG-2.5, mono, stéréo, blocs courts et mixtes, réservoir et intensity stereo. Le corpus reste limité : six des petits fichiers sont des signaux synthétiques, les autres proviennent des vecteurs minimp3, et le grand MP3 est une lecture. Cela ne suffit pas à établir un gain moyen sur de la musique variée.

Les tables Huffman et leur syntaxe appartiennent au standard MP3. N&C est apparenté au codage contextuel avec échappement de type PPM ; REF aux références LZ ; le codage arithmétique et zlib sont des briques connues. L’expérience porte sur la combinaison des cordes et leurs regroupements. Son originalité au sens de l’état de l’art n’est pas établie.

### Reproduction

Java 17 requis. Le projet Maven complet est fourni ; ses moteurs de recherche complémentaires se compilent avec :

```bash
bash build-reservoir.sh
```

Pour les quatre regroupements et leurs contrôles exacts :

```bash
java -Xmx2048m -cp target/classes:target/reservoir-classes io.ivo.mp3.CordsMeasure original.mp3 resultats 0,1,2,3
```

Un quatrième argument peut fournir une archive MP37 pour reprendre ses métadonnées. Utiliser un nouveau dossier pour changer de source de métadonnées ; le cache vérifie le SHA-256 du fichier original.

Le décodeur final utilise des tableaux d’octets pour les cordes ; prévoir 2 Gio de heap pour le grand cas. L’argument `travail-decodage` est conservé pour la compatibilité de l’interface, sans écrire de cordes temporaires.

Pour restituer une archive MP39 sans le fichier d’origine :

```bash
java -Xmx2048m -cp target/classes:target/reservoir-classes io.ivo.mp3.CordsMeasure decode resultats/spectral-s0.ivo restaure.mp3 travail-decodage
```

Pour un corpus :

```bash
bash benchmark-spectral-cords.sh resultats chemin/a.mp3 chemin/b.mp3
java -Xmx1024m -cp target/classes:target/reservoir-classes io.ivo.mp3.SpectralCordsTest chemin/a.mp3 chemin/b.mp3
```

L’exécutable principal antérieur reste inchangé dans son routage MP3. Ce dossier fige une expérience mesurée et reproductible, sans annoncer une intégration qui n’a pas été réalisée.

Contrôle indépendant des archives déjà produites, avec le fichier original utilisé seulement pour la comparaison :

```bash
java -Xmx2048m -cp target/classes:target/reservoir-classes io.ivo.mp3.CordsArchiveValidation resultats dossier-petits-mp3 grand-original.mp3
```

Ce validateur attend les quatre archives de chaque petit fichier dans son sous-dossier et celles du grand fichier dans `resultats/tao`. Le résultat fourni est `PASS final_archives=52 byte_exact=true fresh_process=true`.

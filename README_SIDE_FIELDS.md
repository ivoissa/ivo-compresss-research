# MP3 — cordes de champs et résidus causaux (MP35)

## Explication simple

Cette expérience sépare les informations latérales du MP3 en 21 cordes : longueurs, gains, choix de tables et autres champs. Elle compare les valeurs directes, les écarts à la valeur précédente, les écarts à la médiane des trois valeurs précédentes et un choix par champ. Le son n’est pas réencodé. Les données du réservoir et tous les autres octets sont conservés par les moteurs exacts déjà testés.

Sur 26 fichiers/flux du corpus précédent, les quatre variantes donnent 104 archives .ivo reconstruites exactement. Le meilleur nouveau candidat gagne sur 15 fichiers et augmente la taille sur 11 autres. En conservant le meilleur résultat précédent dans ces 11 cas, le minimum mesuré économise 24 960 octets supplémentaires. Les signaux techniques représentent une grande partie de ce bénéfice ; il ne faut pas assimiler ce corpus à une discothèque.

La séparation par champ sans prédiction gagne déjà sur 10 fichiers. L’ajout des prédictions et du choix par champ économise 701 octets supplémentaires après sélection par rapport au minimum précédent/séparation seule. La contribution de la prédiction reste donc modeste sur ce corpus.

Sur le MP3 Tao de 95 634 889 octets :

| Variante | .ivo complet en octets |
|---|---:|
| Meilleur précédent MP34 | 87 587 830 |
| Champs directs | 87,538,951 |
| Prédiction valeur précédente | 88,223,205 |
| Prédiction médiane des trois précédentes | 88,283,917 |
| Choix du prédicteur par champ | 87,538,972 |

Le meilleur nouveau candidat économise 48 879 octets sur ce fichier. La réduction totale du fichier passe d’environ 8,41 % à 8,47 % (pourcentages arrondis calculés depuis les tailles exactes). Les quatre reconstructions sont exactes et ont le SHA-256 c6e361e833559e842390da8541480ce47096baf61b405da64d9ef10188a2f40c. Les prédictions globales perdent sur ce fichier ; elles ne remplacent donc pas les champs directs.

Le code MP35 est un candidat de recherche avec son décodeur séparé. Le CLI principal reste MP31 ; aucun routeur de production MP35 n’a été ajouté. Les minima présentés sont calculés après mesure des archives réellement écrites.

## Résultats du corpus

Les colonnes V0/V1/V2/V3 de cette table désignent les PROFILS du nouveau MP35 (champs directs/précédent/médiane/choix par champ), et non les versions historiques MP31/33/34.

| Fichier | Meilleur précédent | Profil 0 | Profil 1 | Profil 2 | Profil 3 | Minimum conservé |
|---|---:|---:|---:|---:|---:|---:|
| minimp3/performance/MEANDR_PHASE0.mp3 | 3892658 | 3876395 | 3888777 | 3886973 | 3876219 | 3876219 |
| minimp3/performance/MEANDR_PHASE90.mp3 | 392662 | 390805 | 391798 | 391636 | 390826 | 390805 |
| minimp3/performance/MEANDR90.mp3 | 9653 | 9676 | 9685 | 9690 | 9693 | 9653 |
| minimp3/performance/MIPSTest.mp3 | 320911 | 319465 | 320335 | 320200 | 319484 | 319465 |
| minimp3/performance/noise_meandr.mp3 | 392002 | 390291 | 391845 | 391849 | 390312 | 390291 |
| minimp3/fuzz/l3-compl-cut.mp3 | 251 | 354 | 354 | 354 | 375 | 251 |
| MelNet/music/sample-0.mp3 | 116773 | 116845 | 116882 | 116844 | 116797 | 116773 |
| MelNet/music/sample-1.mp3 | 116879 | 116910 | 116928 | 116893 | 116853 | 116853 |
| MelNet/music_primed/sample-0.mp3 | 115444 | 115515 | 115537 | 115510 | 115498 | 115444 |
| MelNet/music_primed/sample-1.mp3 | 115371 | 115422 | 115487 | 115431 | 115394 | 115371 |
| MelNet/ted_val/sample-0.mp3 | 79290 | 79304 | 79324 | 79316 | 79287 | 79287 |
| MelNet/ted_val/sample-1.mp3 | 49818 | 49853 | 49845 | 49852 | 49849 | 49818 |
| MelNet/blizzard_primed/sample-0.mp3 | 118803 | 118852 | 118847 | 118867 | 118809 | 118803 |
| MelNet/blizzard_primed/sample-1.mp3 | 118191 | 118205 | 118213 | 118243 | 118173 | 118173 |
| MelNet/wavenet_unconditional/maestro/sample-0.mp3 | 117028 | 117007 | 116885 | 116877 | 116882 | 116877 |
| MelNet/wavenet_unconditional/maestro/sample-1.mp3 | 117233 | 117308 | 117181 | 117144 | 117155 | 117144 |
| silence/1-second-of-silence.mp3 | 28254 | 28319 | 28321 | 28323 | 28340 | 28254 |
| silence/10-seconds-of-silence.mp3 | 36271 | 36394 | 36453 | 36440 | 36381 | 36271 |
| Samplelib/sample-3s.mp3 | 48840 | 48933 | 49062 | 48989 | 48883 | 48840 |
| Samplelib/sample-6s.mp3 | 96914 | 96756 | 97068 | 96965 | 96726 | 96726 |
| Samplelib/sample-9s.mp3 | 121070 | 120856 | 121256 | 121612 | 120834 | 120834 |
| Samplelib/sample-speech-1m.mp3 | 927616 | 926233 | 926956 | 927227 | 926128 | 926128 |
| Samplelib/sample-15s-cbr-128kbps.mp3 | 216672 | 216148 | 217204 | 217112 | 216169 | 216148 |
| Samplelib/sample-15s-vbr-v2.mp3 | 208018 | 207336 | 208678 | 208494 | 207357 | 207336 |
| minimp3/l3-test45.bit | 346000 | 347300 | 346089 | 346588 | 346105 | 346000 |
| minimp3/l3-test46.bit | 121833 | 122145 | 121731 | 122050 | 121751 | 121731 |

Sur ces 26 fichiers/flux, somme des tailles originales = 8 585 464 octets ; somme des minima précédents = 8 224 455 ; somme des nouveaux minima conservés = 8 199 495. Cette agrégation est pondérée par les tailles de ce corpus, pas une estimation universelle.

## Partie technique

### Cordes et parcours

Les 21 champs sont : main_data_begin, private_bits, scfsi, part2_3_length, big_values, global_gain, scalefac_compress, window_switching_flag, block_type, mixed_block_flag, table_select[0..2], subblock_gain[0..2], region0_count, region1_count, preflag, scalefac_scale et count1table_select. Les champs absents dans certaines versions/branches ne sont pas inventés ni transmis.

La géométrie est dérivée des en-têtes et du layout : MPEG-1/2/2.5, mono/stéréo, granule et canal. Au décodage, window_switching_flag et les champs restaurés déterminent la branche à parcourir. Chaque visite lit/restaure exactement la largeur de champ prévue. Les compteurs et le padding des cordes sont contrôlés.

Les historiques causaux sont séparés par (champ, version MPEG, mono/stéréo, granule, canal), soit jusqu’à 24 chemins par champ. Un champ absent n’avance pas son historique. Chaque historique contient trois valeurs, initialisées à zéro.

### Résidu bijectif

Pour une largeur w, on pose M=2^w. Avec la prédiction p_t et la valeur x_t :

```text
u_t = (x_t - p_t) mod M
d_t = u_t                    si u_t < M/2
d_t = u_t - M                sinon
z_t = 2*d_t                  si d_t >= 0
z_t = -2*d_t - 1             sinon
```

z_t tient sur w bits. Le décodeur inverse le pliage signé, puis calcule x_t=(p_t+d_t) mod M. Il met à jour son historique après restitution. La prédiction est soit la dernière valeur du même chemin, soit la médiane de ses trois dernières valeurs. Les valeurs directes n’utilisent pas ce pliage.

### Coût réel et codage

Chaque corde est empaquetée MSB-first puis passée au ResearchCordCodec existant : comparaison RAW, N&C, profils N&C, REF+N&C, REF en cordes et zlib. On ne peut donc pas attribuer tous les gains à N&C seul. Dans les 15 nouveaux candidats gagnants, les 315 cordes (champs vides inclus) choisissent : 72 N&C, 139 profils N&C, 2 REF+N&C, 1 REF en cordes, 34 zlib et 67 RAW.

Le profil 3 sélectionne, pour chaque champ, le plus petit codage parmi direct/précédent/médiane et transmet un octet de mode par champ. Son surcoût est inclus dans les tailles complètes ; il peut perdre face à un profil global même si ses cordes sont individuellement plus petites.

### Format expérimental MP35

```text
magic MP35 (4 octets)
format du main_data : ASCII 3 (MP33) ou 4 (MP34), 1 octet
7 champs avec longueur varint : layout, prefix, headers, CRC, side, main, trailing
side : profil (1 octet), puis 21 cordes
pour chaque corde : mode prédictif si profil 3, longueur varint, ResearchCordCodec
```

Les autres cordes du meilleur candidat MP33/MP34 sont recopiées. Le décodeur ne dépend pas d’un fichier de référence externe. Il décode les structures, inverse les cordes de champs, puis restaure main_data selon son format conservé. Le conteneur IVO et son SHA-256 restent comptés.

### Validation

- 104 archives MP35 du corpus : écriture sur disque, relecture, decode, Arrays.equals et SHA-256.
- 4 archives MP35 du MP3 Tao, avec les mêmes contrôles.
- 22 369 620 couples valeur/prédiction testés exhaustivement pour les largeurs 1 à 12 : pliage bijectif et valeur restituée exacte.
- 48 trames de fixtures à offsets connus, avec changements de versions/canaux/branches : les quatre profils restituent exactement les informations latérales ; huit cas de profils invalides/données supplémentaires sont rejetés.
- Projet Java complet recompilé ; SelfTest et DeflateExactTest passent, ainsi que les tests side_info et SemanticSideTest.

Les fichiers audio temporaires du précédent téléchargement n’étant plus disponibles, les 26 entrées ont été restaurées des archives MP33 déjà validées. Leurs tailles et SHA-256 ont été comparés aux mesures précédentes avant les nouveaux essais. Aucun réencodage ni nouvel audio généré n’est utilisé. Le MP3 Tao est l’original déjà restauré et vérifié lors de l’étape réservoir.

### Originalité et limites

Séparer des champs, coder des résidus, employer une médiane et maintenir des contextes causaux sont des techniques connues. L’essai explore leur organisation dans les cordes IVO, sans revendiquer de preuve d’originalité. La réversibilité et les économies de taille sont mesurées ; leur généralisation n’est pas établie. Aucun gain de 50 % n’est annoncé.

## Exécution

```bash
bash build-reservoir.sh
# Construire d’abord les candidats précédents si nécessaire :
java -Xmx1024m -cp target/reservoir-classes:target/classes io.ivo.mp3.CorpusTrial fichier.mp3 candidats
# Fournir le meilleur MP33 ou MP34 mesuré du même fichier :
java -Xmx2048m -cp target/reservoir-classes:target/classes io.ivo.mp3.SideMeasure fichier.mp3 candidats/v4m2.ivo nouveaux
# Pour une ancienne archive MP33 avec original absent :
java -cp target/reservoir-classes:target/classes io.ivo.mp3.RecoverInputs candidats/v3.ivo original-restitue.mp3
```

Le chemin candidats/v4m2.ivo est un exemple : choisir le plus petit MP33/MP34 réellement mesuré. Si V1 ou STORE gagne, les conserver dans la comparaison finale. Les sources de cette expérience sont dans research-experiments/mp3-reservoir/src ; le socle Maven est dans src et pom.xml. Les commandes demandent Java 17. Les fichiers audio, archives .ivo volumineuses et JAR ne sont pas inclus.

Les mesures, SHA-256, détails des codeurs et journaux sont dans research-results/2026-10-03/mp3-side-fields. Les anciennes mesures des autres moteurs sont conservées dans leurs dossiers et ne constituent pas de nouveaux essais.

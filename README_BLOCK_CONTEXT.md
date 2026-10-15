# MP3 — cordes conditionnées par type de bloc et canal (MP36)

## Explication simple

Cette étape reprend les champs exacts de MP35 et les regroupe selon le type de bloc MP3, puis éventuellement le canal. Elle compare trois organisations : regroupement par type de bloc, regroupement par type et canal, ou codage indépendant de chaque type de bloc. Tous les bits du MP3 restent présents ; aucun décodage/réencodage audio n’a lieu.

Sur les 26 fichiers/flux du corpus : 78 nouveaux .ivo écrits, relus et reconstruits exactement ; 4 fichiers améliorés et 22 pour lesquels le meilleur nouveau candidat augmente la taille. Le minimum mesuré, en conservant l’ancien modèle dans les cas défavorables, économise 999 octets supplémentaires.

Sur le gros MP3 Tao de 95 634 889 octets :

| Variante | .ivo complet (octets) |
|---|---:|
| Meilleur précédent MP35 | 87 538 951 |
| Regroupement par type de bloc (schéma 0) | 87507554 |
| Regroupement par type et canal (schéma 1) | 87509597 |
| Cordes indépendantes par type de bloc (schéma 2) | 87439040 |

Les trois reconstructions sont exactes. Le gain supplémentaire est de 99911 octets. La réduction totale de l’original passe de 8.465% à 8.570%. Cela représente un progrès modeste, pas un gain généralisé à tous les MP3.

MP36 reste un candidat de recherche avec son décodeur séparé. Le CLI principal conserve MP31. Les anciens candidats sont maintenus comme références mesurées ; aucun remplacement systématique ni intégration au routeur de production n’a été effectué.

## Mesures du corpus

| Fichier | Meilleur précédent | Schéma 0 | Schéma 1 | Schéma 2 | Minimum conservé |
|---|---:|---:|---:|---:|---:|
| minimp3/performance/MEANDR_PHASE0.mp3 | 3876219 | 3876281 | 3877587 | 3875852 | 3875852 |
| minimp3/performance/MEANDR_PHASE90.mp3 | 390805 | 390847 | 390875 | 391080 | 390805 |
| minimp3/performance/MEANDR90.mp3 | 9653 | 9676 | 9676 | 9814 | 9653 |
| minimp3/performance/MIPSTest.mp3 | 319465 | 319480 | 319632 | 319614 | 319465 |
| minimp3/performance/noise_meandr.mp3 | 390291 | 390162 | 390262 | 390494 | 390162 |
| minimp3/fuzz/l3-compl-cut.mp3 | 251 | 345 | 345 | 345 | 251 |
| MelNet/music/sample-0.mp3 | 116773 | 116853 | 116853 | 116968 | 116773 |
| MelNet/music/sample-1.mp3 | 116853 | 116910 | 116910 | 117072 | 116853 |
| MelNet/music_primed/sample-0.mp3 | 115444 | 115496 | 115496 | 115608 | 115444 |
| MelNet/music_primed/sample-1.mp3 | 115371 | 115432 | 115432 | 115536 | 115371 |
| MelNet/ted_val/sample-0.mp3 | 79287 | 79304 | 79304 | 79422 | 79287 |
| MelNet/ted_val/sample-1.mp3 | 49818 | 49844 | 49844 | 49962 | 49818 |
| MelNet/blizzard_primed/sample-0.mp3 | 118803 | 118839 | 118839 | 118966 | 118803 |
| MelNet/blizzard_primed/sample-1.mp3 | 118173 | 118214 | 118214 | 118381 | 118173 |
| MelNet/wavenet_unconditional/maestro/sample-0.mp3 | 116877 | 117007 | 117007 | 117126 | 116877 |
| MelNet/wavenet_unconditional/maestro/sample-1.mp3 | 117144 | 117305 | 117305 | 117409 | 117144 |
| silence/1-second-of-silence.mp3 | 28254 | 28304 | 28304 | 28304 | 28254 |
| silence/10-seconds-of-silence.mp3 | 36271 | 36379 | 36379 | 36379 | 36271 |
| Samplelib/sample-3s.mp3 | 48840 | 48929 | 48894 | 49059 | 48840 |
| Samplelib/sample-6s.mp3 | 96726 | 96764 | 96816 | 96884 | 96726 |
| Samplelib/sample-9s.mp3 | 120834 | 120915 | 121042 | 121100 | 120834 |
| Samplelib/sample-speech-1m.mp3 | 926128 | 926222 | 926222 | 926724 | 926128 |
| Samplelib/sample-15s-cbr-128kbps.mp3 | 216148 | 216201 | 215798 | 216635 | 215798 |
| Samplelib/sample-15s-vbr-v2.mp3 | 207336 | 207322 | 207183 | 207604 | 207183 |
| minimp3/l3-test45.bit | 346000 | 347294 | 346715 | 347592 | 346000 |
| minimp3/l3-test46.bit | 121731 | 122139 | 122194 | 122258 | 121731 |

Les quatre gains sont : MEANDR_PHASE0, 367 octets ; noise_meandr, 129 octets ; musique CBR 128 kb/s, 350 octets ; musique VBR, 153 octets. Le corpus inclut des signaux techniques, silences, extraits MelNet/WaveNet synthétiques/amorcés et échantillons Samplelib. Il ne représente pas une discothèque.

## Partie technique

### Contextes reconstruits par le décodeur

Cinq classes sont dérivées des champs déjà nécessaires à la restitution du MP3 :

| Classe | Condition |
|---|---|
| 0 | window_switching_flag = 0 : bloc long |
| 1 | window_switching_flag = 1, block_type = 1 |
| 2 | block_type = 2, mixed_block_flag = 0 |
| 3 | block_type = 3 |
| 4 | block_type = 2, mixed_block_flag = 1 |

Les champs window_switching_flag, block_type et mixed_block_flag sont codés séparément et restaurés en premier. Les en-têtes fournissent versions MPEG, mono/stéréo et nombre de granules. Le décodeur connaît donc la classe et le canal de chaque occurrence de chaque champ avant de décoder les autres cordes. Il n’existe aucun tableau de permutations ou d’étiquettes de contexte transmis en plus. Les champs qui précèdent les indicateurs dans le fichier original peuvent être regroupés par le type de bloc courant, car l’ordre de décodage du conteneur restaure d’abord ces indicateurs.

main_data_begin, private_bits et scfsi conservent leur ordre ; les autres champs non structurels suivent le regroupement. À classe égale, l’ordre initial est conservé. Les branches absentes ne produisent aucune valeur artificielle.

### Trois organisations testées

- Schéma 0 : pour chaque champ, concaténer ses valeurs par classe 0..4, avec empaquetage continu des bits, puis coder une seule corde.
- Schéma 1 : même principe avec dix groupes (classe, canal), dans l’ordre 2*classe+canal.
- Schéma 2 : coder séparément les groupes actifs de chaque champ/classe, avec remplissage du dernier octet recalculable. Les groupes vides ne sont pas transmis.

Le codage utilise le ResearchCordCodec existant : portefeuille RAW, N&C, profils N&C, REF+N&C, REF en cordes et zlib. Le schéma 2 donne notamment des états N&C indépendants par champ/type quand N&C est sélectionné. Les schémas 0/1 réorganisent les données sans modifier les formules internes de N&C. Il ne faut donc pas attribuer les gains à un nouveau modèle probabiliste autonome ou à N&C seul. Aucun résidu prédictif supplémentaire n’a été ajouté dans cette étape ; les anciens résidus MP35 restent parmi les candidats de référence.

À titre de description des modèles N&C indépendants, le conditionnement visé est P(octet | champ, classe de bloc, historique local). Les longueurs, tags de mode, profils et autres métadonnées sont inclus dans les tailles .ivo mesurées. Fragmenter les cordes peut réduire la régularité statistique ou multiplier les en-têtes ; la sélection par taille finale reste nécessaire.

### Format expérimental MP36

```text
magic MP36 (4 octets)
format main_data conservé : ASCII 3 ou 4 (1 octet)
7 champs de longueur varint : layout, prefix, headers, CRC, side, main, trailing
side : schéma (1 octet)
3 cordes de contrôle : window_switching_flag, block_type, mixed_block_flag
puis les 18 autres champs, dans l’ordre de leurs identifiants
schémas 0/1 : une corde par champ non vide
schéma 2 : une corde par groupe actif, dans l’ordre des classes
```

Les nombres de valeurs et de bits par groupe sont dérivés des en-têtes et des cordes de contrôle. Pour les schémas 0/1, les offsets de groupes sont les sommes préfixes de ces nombres de bits. Pour le schéma 2, le padding de chaque groupe est vérifié. Les lecteurs doivent consommer exactement leurs plages. Les autres cordes du meilleur MP33/MP34/MP35 fourni en entrée sont recopiées, sans référence externe au décodage.

### Validation réalisée

- 78 archives du corpus et 3 archives Tao : écriture, relecture du conteneur, décodage, comparaison directe Arrays.equals et SHA-256.
- Tailles et empreintes des 26 entrées comparées au corpus précédent. Les entrées sont les originaux restaurés d’archives MP33 antérieurement validées, sans réencodage audio.
- 90 trames de fixtures à offsets connus, couvrant versions MPEG-1/2/2.5, mono/stéréo, cinq classes et changements de géométrie. Les trois schémas restituent les informations latérales exactement ; six cas malformés sont rejetés.
- Compilation Java 17 du projet et des expériences. SelfTest, DeflateExactTest, Layer3SideInfoTest, SemanticSideTest et BlockSideTest passent.
- Le décodeur expérimental restaure aussi les candidats de référence fournis à chaque essai avant de tester la nouvelle transformation.

SHA-256 du Tao original/restauré : c6e361e833559e842390da8541480ce47096baf61b405da64d9ef10188a2f40c. Les SHA-256 du corpus et tous les journaux sont joints.

### Originalité et limites

Le regroupement réversible et le conditionnement statistique sont des techniques connues. L’essai porte sur leur combinaison avec les cordes IVO et leurs coûts complets, sans revendication de preuve d’originalité. Il établit un gain sur quelques fichiers testés ; il ne valide pas un moteur universel. Les limitations des parseurs et des formats de recherche précédents restent applicables.

## Exécuter en Java 17

```bash
bash build-reservoir.sh
java -Xmx2048m -cp target/reservoir-classes:target/classes io.ivo.mp3.BlockMeasure original.mp3 meilleur-precedent.ivo nouveaux-resultats
```

Le candidat de référence doit contenir MP33, MP34 ou MP35 et correspondre au même fichier. Le programme vérifie la taille, le SHA-256 et la reconstruction de cette référence avant les essais. Les trois .ivo expérimentaux sont écrits dans nouveaux-resultats ; le décodeur MP36 est ReservoirTrial.decodeBlockCandidate. Les sources sont dans research-experiments/mp3-reservoir/src avec le socle Maven dans src et pom.xml. Les JAR, fichiers audio et archives .ivo volumineuses ne sont pas inclus.

Les fichiers research-results/2026-10-03/mp3-block-context contiennent mesures, empreintes, code des scripts de recherche en .py.txt et journaux. Les anciens dossiers de résultats sont conservés comme historique, pas présentés comme de nouveaux essais.

## Suite proposée

Le bénéfice moyen supplémentaire sur le petit corpus est faible. Conserver ce candidat optionnel et déplacer l’effort vers les données main_data : séparation par granule/canal, puis éventuelle représentation Huffman exacte. Ces deux pistes restent à implémenter et mesurer.

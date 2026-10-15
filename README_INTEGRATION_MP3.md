# MP39/MP40 dans le programme principal

## Explication simple

Le programme essaie désormais les cordes spectrales MP39 et les contextes MP40, en plus de son ancien moteur MP3 et de STORE. Chaque candidat doit reconstruire le fichier exactement ; le plus petit conteneur complet gagne. Aucun original, archive de référence ou dictionnaire externe n’est nécessaire pour décompresser.

```bash
bash build.sh
java -Xmx2g -jar target/ivo-0.8.0-research.jar compress original.mp3 original.ivo
java -Xmx2g -jar target/ivo-0.8.0-research.jar decompress original.ivo restaure.mp3
```

IntelliJ et Maven utilisent les mêmes sources dans `src/main/java`. `mvn test package` exécute également les tests de l’intégration. `build-reservoir.sh` conserve les tests de recherche supplémentaires.

Le balayage exhaustif de MP39/MP40 est coûteux : il multiplie les analyses et crée des cordes temporaires supprimées en fin de compression. La décompression lit uniquement le candidat sélectionné. Cette livraison n’établit pas de performance moyenne sur la musique.

## Partie technique

`Mp3SpectralEngine(false)` conserve le type 18 et la signature MP39 ; `Mp3SpectralEngine(true)` conserve le type 19 et MP40. Les numéros existants ne sont pas réaffectés. Chaque moteur vérifie sa signature ; le routeur vérifie ensuite longueur originale et SHA-256.

MP39 balaie les schémas 0 à 3. MP40 construit ses bases 0 et 2 directement depuis l’entrée, puis balaie les schémas 4 à 11. Les bases sont internes à la compression et ne sont pas des dépendances de l’archive finale. La construction sans seed utilise le format de métadonnées et le profil side_info définis par `CordsMeasure.metadata` ; elle peut donc différer en taille des expériences historiques ayant réutilisé un seed MP37. On ne promet pas qu’elle reproduit automatiquement leur meilleur résultat.

Chaque variante vérifie `Arrays.equals(original, restored)` et l’égalité des statistiques spectrales avant d’être comparée. À identifiant, nom, taille originale et SHA-256 identiques, le coût de l’archive est monotone en fonction de la taille du payload (VarInt inclus). Le minimum interne par payload équivaut donc au minimum du conteneur. Le routeur mesure le conteneur complet entre moteurs.

Les tables de réémission Huffman sont désormais dans `HuffmanCodes`, sans dépendance de production envers une classe de test. Les sources du codec sont déplacées dans le module Maven principal ; les expériences et tests restants utilisent ce même codec.

## Validation réellement exécutée

- Tests runtime : 12 cas MP39/MP40 couvrant MPEG-1/2/2.5, mono/stéréo, préfixe, traîne, intégrité SHA, rejet du mauvais moteur et repli sur fichier non analysable.
- Tests précédents : 99 260 cas Huffman, 162 quads, facteurs d’échelle, réservoir, side_info, cordes et entrées malformées.
- Dix archives d’un MP3 synthétique ont été décompressées par dix nouveaux processus du CLI principal, puis comparées octet pour octet à la source.
- L’archive MP40 Tao existante de 82 851 262 octets a été décompressée par le CLI principal : 95 634 889 octets restaurés, SHA-256 vérifié. Ce contrôle est une lecture de l’archive antérieure, pas une nouvelle mesure de compression de Tao.
- Sur le MP3 synthétique de 17 180 octets, le routeur conserve MP31 : 14 444 octets, contre 14 956 pour MP39 et 15 013 pour MP40. L’intégration ne crée donc pas de régression sur ce cas.

Rapports : `research-results/2026-10-04/runtime-integration`. La compilation et les tests ont été exécutés via les scripts Java 17 ; Maven n’a pas été exécuté dans cet environnement.

## Début de la phase MP4

Les moteurs V1/V2/V3 servent de référence, sans nouvelle transformation vidéo dans cette livraison. Le fichier WhatsApp original donne 5 427 643 → 5 415 863 octets avec V3, soit 0,217 % de réduction ; décompression dans un nouveau processus et comparaison directe exactes. Il contient H.264 et AAC.

Le corpus de démarrage ajoute deux vidéos H.264 synthétiques et un extrait réel remuxé en MP4 fragmenté. Leurs résultats, leur origine et les contrôles exacts figurent dans `research-results/2026-10-04/mp4-baseline`. Ces variantes dérivées ne constituent pas un corpus indépendant de vidéos naturelles.

Prochaine expérience : mesurer le coût des cordes et tester la séparation des en-têtes NAL selon le codec, puis les contextes temporels sur les champs effectivement redondants. Les gains du flux vidéo ne sont pas supposés à partir du gain des seules métadonnées.

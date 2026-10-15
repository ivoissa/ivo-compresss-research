# MP42 : contexte d’énergie spectrale — version 20

## Résultat

Une hypothèse différente des résidus temporels MP41 donne un gain réel sur Tao : **82 291 497 octets IVO**, contre le meilleur précédent **82 851 262**. Le gain supplémentaire est **559 765 octets**, soit **0,676 %** de l’archive précédente. La décompression rend les 95 634 889 octets originaux, avec la même empreinte SHA-256 et le même nom de fichier dans le conteneur.

| Tao, conteneur complet | Octets |
| --- | ---: |
| Meilleur précédent MP40 | 82 851 262 |
| Énergie du groupe spectral précédent, s15 | 82 318 570 |
| Énergie du même groupe du granule précédent, s16 | 82 291 497 |
| Contrôle par position, s17 | 83 480 877 |
| Contrôle sans énergie, s18 | 83 508 644 |

Les deux contextes d’énergie battent les deux contrôles. Le meilleur bat le contrôle s18 de 1 217 147 octets, en incluant tous les coûts.

Sur les onze petits fichiers, l’énergie gagne uniquement sur l3-test45 : **323 394**, contre **325 276**, soit 1 882 octets. Les dix autres conservent leur meilleur précédent si l’on ne considère que les contextes d’énergie. Un contrôle par position gagne 57 octets sur le nouveau sinus stéréo 44 100 Hz : ce gain n’est pas attribué à l’énergie. Aucune amélioration générale de la musique ou de la parole n’est démontrée.

## Hypothèse et causalité

Les amplitudes restent `abs(v)-1`, sans différence avec une amplitude prédite et sans conversion PCM. L’énergie fournit uniquement un contexte de probabilité via la répartition des amplitudes en cordes ; les signes, supports, facteurs, résidus et bits inutilisés restent ceux de la référence MP39/40.

Chaque groupe contient seize positions dans l’ordre Huffman. Son énergie est la somme des carrés des amplitudes, zéros inclus. Les quatre classes fixées sont <16, [16,256), [256,4096), ≥4096. Elles s’ajoutent aux quatre plages de position historiques et au canal, soit 32 cordes d’amplitudes. Les 40 cordes de base sont conservées, leurs anciennes amplitudes sont vides, pour 72 cordes au total, métadonnées comprises.

s15 consulte uniquement le groupe précédent entièrement reconstruit du granule actuel. s16 consulte le groupe correspondant du granule précédent du même canal. Au démarrage, après une partie vide, lors d’une géométrie incompatible, ou pour une partie intensity actuelle, le contexte historique s16 prend zéro. Aucune donnée future n’est utilisée ; le décodeur prend les mêmes décisions à partir des amplitudes qu’il a déjà reconstruites.

s17 répartit par `(position/16)%4`, sans énergie. s18 fixe la classe à zéro. Ces deux contrôles utilisent le même nombre de cordes et le même portefeuille de codeurs. La référence fournissant les champs non modifiés est authentifiée par taille et SHA-256 ; l’archive finale ne dépend plus de cette référence.

## Validation et reproduction

Douze fichiers × quatre variantes : **48 archives**. Les encodages vérifient octets et statistiques spectrales ; les archives sont également décompressées par 48 nouveaux processus du CLI, avec contrôle SHA-256. EnergyContextTest vérifie les seuils, les plages de cordes, l’absence de dépendance au futur et la désactivation de l’historique incompatible. Les décodeurs MP39/MP40/MP41 sont conservés.

MP42 utilise l’identifiant 26, enregistré pour lecture uniquement. Les performances et le gain sur d’autres enregistrements restent à établir avant une promotion automatique. Les quatre archives Tao sont volumineuses et ne sont pas dans le ZIP source ; leurs tailles, empreintes et validations y figurent.

```sh
bash build.sh
# Référence MP39 position/canal, ou MP40 compatible existant :
java -Xmx2g -cp target/classes io.ivo.mp3.CordsMeasure original.mp3 reference 2
java -Xmx2g -cp target/classes io.ivo.mp3.EnergyMeasure original.mp3 reference/spectral-s2.ivo essais
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli decompress essais/energy-s16.ivo restaure.mp3
```

Résultats complets : `research-results/2026-10-04/mp3-energy`. La comparaison inclut les meilleurs MP41 antérieurs, lorsque ceux-ci avaient déjà gagné ; elle ne se limite pas à une ancienne référence moins performante.

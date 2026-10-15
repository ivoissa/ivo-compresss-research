# MP41 : échelles par bande et prédiction temporelle — version 13

## Conclusion

La prédiction directe depuis le granule précédent ne justifie pas une promotion automatique. Sur Tao, les trois candidats sont plus gros que MP40. Le classement des amplitudes par échelle effective améliore deux vecteurs publics de peu ; le prédicteur ajusté gagne quelques octets sur deux nouveaux signaux synthétiques. Aucun gain moyen représentatif de la musique ou de la parole n’est établi.

| Tao | Conteneur IVO complet |
|---|---:|
| MP40 précédent | 82 851 262 |
| Échelle effective par bande seule, s12 | 83 137 499 |
| Résidu au granule précédent, s13 | 91 929 968 |
| Résidu au prédicteur ajusté, s14 | 91 868 844 |

MP40 reste sélectionné. Le candidat s12 perd 286 237 octets ; les prédictions temporelles perdent environ neuf millions d’octets. Ces archives incluent les mêmes nom et SHA-256. Les petits essais reprennent les autres champs de leur référence MP39 s2 ; Tao reprend ceux du MP40 existant.

| Améliorations seulement | Ancien meilleur | Nouveau candidat | Octets gagnés |
|---|---:|---:|---:|
| MEANDR_PHASE0, vecteur public | 3 759 232 | 3 750 504 (s12) | 8 728 |
| l3-test45, vecteur public | 325 370 | 325 276 (s12) | 94 |
| Nouveau sinus 44 100 Hz mono | 25 243 | 25 033 (s14) | 210 |
| Nouveau sinus 22 050 Hz mono | 14 382 | 14 335 (s14) | 47 |

Les huit autres fichiers gardent leur meilleur précédent. Les deux derniers cas sont synthétiques. La copie historique generated-44100-1 et le nouveau sinus homonyme ont des empreintes différentes et sont distingués par leur groupe dans les mesures. Les comparaisons historiques emploient le meilleur précédent sauvegardé ; les six nouveaux signaux ont un portefeuille précédent remesuré sur les mêmes octets : STORE, N&C, REF+N&C, MP3 V1, MP39 et MP40. Une réussite sur un signal synthétique ne démontre pas une réussite sur la parole ou la musique.

## Représentation exacte et causalité

Les nouvelles informations de side_info lues sont scalefac_scale, preflag et les trois subblock_gain. L’ordre et le nombre de bits conservés restent identiques à MP39/40. TemporalScale reconstruit les facteurs implicites à zéro et ceux réutilisés par scfsi. Les partitions MPEG-1/2/2.5, blocs longs/courts/mixtes et la syntaxe intensity des facteurs sont traités. Les sorties par position correspondent à l’ordre Huffman, avant réordonnancement des fenêtres courtes.

L’échelle effective est représentée par un exposant entier en quarts de log2 :

`E = global_gain - (facteur_effectif << (scalefac_scale+1)) - 8*subblock_gain - correction_MS`

Le préaccent long est intégré au facteur effectif lorsque preflag est actif. Les gains des fenêtres courtes sont appliqués uniquement à leur fenêtre ; la correction MS vaut deux. Le dernier groupe implicite conserve des facteurs zéro. Aucune reconstruction PCM n’intervient dans le format.

s12 classe MAGNITUDE selon E, avec les seuils fixés avant les mesures : <140, [140,170), [170,200), ≥200. Les plages de position et le canal restent ceux de la base MP39 s2. Il stocke abs(v)-1 comme auparavant.

s13 stocke le résidu signé abs(v)-abs(v_précédent). s14 utilise :

`prédiction = arrondi(abs(v_précédent) * 2^((E_précédent-E_actuel)*3/16))`

Le facteur 3/16 provient du rapport entre l’échelle et la puissance 4/3 de la déquantification MP3. C’est un prédicteur expérimental, pas une déquantification exacte ni un changement des coefficients. Une table calculée par StrictMath fixe le calcul ; le prédicteur est borné entre 1 et 8206. Les deltas d’exposant hors [-255,255] prennent une borne déterministe. Les résidus sont ZigZag + VarInt, puis soumis au même portefeuille N&C, REF+N&C, DEFLATE et brut que MP40.

L’historique est séparé par canal et avance à chaque granule. Il inclut big_values, count1 et les zéros terminaux. Il est désactivé au début, après une partie vide, lors d’un changement de géométrie spectrale, et pour les parties intensity. Le décodeur calcule le même historique avec les coefficients qu’il a déjà reconstruits ; aucun historique ni facteur n’est transmis une seconde fois.

Chaque variante compte 72 cordes. Les anciennes cordes MAGNITUDE sont vides ; les autres cordes de la base sont conservées. Tous les en-têtes et toutes les cordes vides sont comptés. SUPPORT et SIGN ne changent pas. Le payload est MP41 et l’identifiant expérimental 23 ; schémas 12..14. Les anciennes archives MP39/40 gardent leurs schémas et représentations. Le CLI principal décompresse MP41 ; le routeur ne le sélectionne pas automatiquement.

## Vérifications

Trois candidats sur douze sources : 36 archives. Chaque encodage est restauré et comparé avec l’original et ses statistiques spectrales ; les archives sont aussi rouvertes dans des processus distincts via le CLI principal, avec contrôle longueur et SHA-256. Les rapports de ces vérifications sont dans research-results/2026-10-04/mp3-temporal.

Un oracle minimp3 instrumenté relève les exposants effectifs après son propre décodage des facteurs, puis les répète selon ses propres bandes. Les comptes et empreintes FNV-1a concordent avec Java sur les douze sources, dont 529 161 984 positions pour Tao. Les sommes ne sont pas des vecteurs PCM et ne prouvent pas tous les flux possibles. Source CC0 conservée dans research-experiments/mp3-scale-oracle ; les modifications ajoutent uniquement la mesure. Aucune décision du décodeur tiers n’est remplacée par un résultat Java.

TemporalScaleTest vérifie les groupes scfsi, les facteurs implicites, preflag, les trois fenêtres courtes, les prédicteurs, les offsets de side_info et le rejet de deux comptes de facteurs malformés. Un conteneur tronqué est rejeté par le CLI. Les tests généraux et MP39/40 passent. Les corpus restent limités ; ils n’épuisent pas les combinaisons de syntaxe et de paramètres MP3.

## Reproduction

```bash
bash build-reservoir.sh
# Référence MP39 s2 créée si nécessaire :
java -Xmx2g -cp target/classes io.ivo.mp3.CordsMeasure original.mp3 reference 2
java -Xmx2g -cp target/classes io.ivo.mp3.TemporalMeasure original.mp3 reference/spectral-s2.ivo essais 12,13,14
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli decompress essais/temporal-s12.ivo restaure.mp3
# Échelles : comparer la même ligne SCALE des deux programmes.
java -Xmx2g -cp target/classes io.ivo.mp3.ScaleInspect original.mp3
cc -O2 research-experiments/mp3-scale-oracle/oracle.c -lm -o /tmp/ivo-scale-oracle
/tmp/ivo-scale-oracle original.mp3
```

TemporalMeasure accepte aussi une archive MP40 de base position/canal (schémas 4..7), notamment celle de Tao. La référence sert à construire le candidat ; le conteneur final est autonome. Une référence de base globale est refusée.

Les trois grandes archives perdantes ne sont pas intégrées au ZIP source ; leurs tailles et les vérifications sont conservées. Les petites archives et les six nouveaux MP3 synthétiques sont inclus. Aucun JAR, exécutable natif ou fichier Python dans le livrable.

## Décision et pistes

On conserve MP40 sur Tao et les meilleurs précédents sur les autres pertes. Les gains limités ne justifient pas de ralentir la compression automatique avec ce balayage. La prédiction ponctuelle depuis le granule précédent échoue sur le cas de parole testé ; la corrélation temporelle utile ne doit pas être supposée.

Si l’on reprend le MP3, une expérience différente serait de conditionner un modèle de probabilités sur l’énergie d’une bande précédente, tout en gardant les amplitudes brutes ; elle doit être mesurée séparément. Pour chercher un meilleur rapport potentiel/effort, le prochain axe conseillé reste la prédiction spatiale des coefficients JPEG, avec comparaison aux références spécialisées. Aucun gain de ces prochaines pistes n’est annoncé.

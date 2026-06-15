# Algorithm Challenge — Query Engine

Answers a batch of 10,000 queries across three kinds against the 50,000-record jumbo dataset.

## How to run

```bash
export ASSESSMENT_BASEURL=https://ca-seassessment-api-dev.happywater-190f264d.northcentralus.azurecontainerapps.io
export ASSESSMENT_APIKEY=sa_...

/path/to/mvn spring-boot:run \
  -Dspring-boot.run.arguments="solve-algorithm"
```

The command:
1. Mints a 60-second bulk-download token (`POST /api/v1/dataset/jumbo/bulk-request`)
2. Redeems it to fetch all 50,000 records in one HTTP response (~10 MB JSON)
3. Fetches the 10,000-query batch (`GET /api/v1/challenges/algorithm/queries`)
4. Builds three in-memory indices in a single O(N) pass
5. Answers all 10,000 queries in O(1) or O(log N) each
6. Submits the SHA-256 digest via `POST /api/v1/submit`

## Implementation

`src/main/java/com/assessment/algorithm/QueryEngine.java`

### Indices

| Query type    | Index                          | Complexity  |
|---------------|--------------------------------|-------------|
| `count`       | `HashMap<(segment,sc), int>`   | O(1)        |
| `exists`      | `HashSet<(ep,method,sc,seg)>`  | O(1)        |
| `range_count` | sorted `int[]` + binary search | O(log N)    |

### Digest

```
SHA-256(answers[0] + "," + answers[1] + "," + … + answers[9999])
```

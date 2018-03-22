# spames
Spams an elasticsearch with a zillion concurrent updates.

## Run it

First, start up an Elasticsearch instance locally. I use Docker:

```
docker run -p 9200:9200 -p 9300:9300 -d -e "discovery.type=single-node" docker.elastic.co/elasticsearch/elasticsearch:6.2.3
```

Then:

1. Open the `build.sbt` in IntelliJ.
2. Open `SpamES.scala`.
3. Right-click inside the `SpamES` object and hit Run.

If you just want to hit the Run button on your IntelliJ toolbar, you'll need to edit your run configuration (Run > Edit Configurations). The Main Class should say `net.floomi.spames.SpamES$` -- note the `$` at the end.

## Results

### What we generate

We generate 1000 documents, each around 100-150KB, and upload them in batches of 100. Each document has [a common set of 5 fields](https://github.com/helgridly/spames/blob/master/src/main/scala/net/floomi/spames/SpamES.scala#L54) (covering the most common datatypes) and up to 100 "junk" fields, with randomly generated strings as keys and [a range of possible datatypes as values](https://github.com/helgridly/spames/blob/master/src/main/scala/net/floomi/spames/SpamES.scala#L41).

This is an extreme dataset in a couple of ways:
* There's 100+MB of it. In comparison, FireCloud's infamous "TSV timeout" issue starts rearing its head [around the 2.2MB mark](https://broadinstitute.atlassian.net/browse/GAWB-2514).
* The fields are _extremely_ irregular. Attribute names are _far_ less distributed in the FireCloud space: samples in the same workspace will share the majority of their fields.

### Results

```
Thu Mar 22 18:26:25 EDT 2018 populating 1000 entities...
Thu Mar 22 18:26:43 EDT 2018 one batch done
Thu Mar 22 18:27:08 EDT 2018 one batch done
Thu Mar 22 18:27:44 EDT 2018 one batch done
Thu Mar 22 18:28:38 EDT 2018 one batch done
Thu Mar 22 18:29:49 EDT 2018 one batch done
Thu Mar 22 18:31:15 EDT 2018 one batch done
Thu Mar 22 18:33:04 EDT 2018 one batch done
Thu Mar 22 18:35:38 EDT 2018 one batch done
Thu Mar 22 18:38:20 EDT 2018 one batch done
Thu Mar 22 18:41:22 EDT 2018 one batch done
Thu Mar 22 18:41:22 EDT 2018 populated

Thu Mar 22 18:41:22 EDT 2018 doing 1000 writes to 10 entities in 20 threads...
Thu Mar 22 18:41:38 EDT 2018 done
```

It takes increasingly longer to add batches of 100, likely because of the distribution of fields.

A lot of concurrent writes to few entities with a normal-sized threadpool are processed in a reasonable time, albeit with some retries because of optimistic locking exceptions.

## Things I learned along the way

e4s returns a `Future[Either]` for all Elasticsearch calls, which if you're used to Futures throwing the exception when you `await` on it, you're gonna think a bunch of things are working when they're not.

The default socket timeout is 100ms, which is way too low for even a search.

Elasticsearch [recommends a bulk size of 5-15MB](https://www.elastic.co/guide/en/elasticsearch/guide/current/bulk.html#_how_big_is_too_big), else performance drops. My initial attempt was loading 1000 145KB documents (lots over the limit!) and I was hitting my increased socket timeout of 5 minutes. Breaking them up into batches of 100 uploaded them all in 16 minutes.

Elasticsearch [doesn't immediately make results available to search](https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-refresh.html). You can force a refresh on every write by setting `RefreshPolicy.Immediate`, but ES warns this can cause performance issues until you give it a chance to fully rebuild the index.

Elasticsearch [warns against having too many fields in an index](https://www.elastic.co/blog/found-beginner-troubleshooting#keyvalue-woes). By default, the limit is 1000, which I easily hit in this test as I generate 100 unique fields for each document.

## Parameters

### Configuration

* The location of the ES server or the request timeout (in the `val client` declaration)
* The number of entities written to the ES server (`numEntities` in `run()`)

### Tuning the test

It's all in the `run()` method. It creates a bunch of Futures that all try to write to the same small set of entities.

`numThreads` is your effective concurrency factor -- the size of the threadpool the Futures execute in.

`numEntitiesWrittenTo` is the number of entities to write to. If it's significantly larger than `numThreads`, you're unlikely to do any concurrent writes.

`numWrites` is mainly a measure of "how long should we keep trying" under those conditions.

`numEntities` is "how much other junk is there in the database to slow down indexing". It takes a while to populate the database (1000 entities is 7 seconds or so), so if you're running this frequently, it might be quicker to populate once and then comment out the calls to `populateEntities` and `deleteESIndex`. 
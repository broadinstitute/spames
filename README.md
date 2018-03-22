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

## Fiddle with things

### Configuration

* The location of the ES server or the request timeout (in the `val client` declaration)
* The number of entities written to the ES server (`numEntities` in `run()`)

### Tuning the test

It's all in the `run()` method. It creates a bunch of Futures that all try to write to the same small set of entities.

`numThreads` is your effective concurrency factor -- the size of the threadpool the Futures execute in.

`numEntitiesWrittenTo` is the number of entities to write to. If it's significantly larger than `numThreads`, you're unlikely to do any concurrent writes.

`numWrites` is mainly a measure of "how long should we keep trying" under those conditions.

`numEntities` is "how much other junk is there in the database to slow down indexing". It takes a while to populate the database (1000 entities is 7 seconds or so), so if you're running this frequently, it might be quicker to populate once and then comment out the calls to `populateEntities` and `deleteESIndex`. 

## Things I learned

e4s returns a `Future[Either]` for all Elasticsearch calls, which if you're used to Futures throwing the exception when you `await` on it, you're gonna think a bunch of things are working when they're not.

The default timeout is 100ms, which is way too low for a search.

Elasticsearch [doesn't immediately make results available to search](https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-refresh.html). You can force a refresh on every write by setting `RefreshPolicy.Immediate`, but ES warns this can cause performance issues until you give it a chance to fully rebuild the index.

Elasticsearch [warns against having too many fields in an index](https://www.elastic.co/blog/found-beginner-troubleshooting#keyvalue-woes). By default, the limit is 1000, which I easily hit in this test as I generate 100 unique fields for each document.


## Things untested

Whether concurrent writes make for data loss.
* ES has [optimistic concurrency control](https://www.elastic.co/guide/en/elasticsearch/guide/current/optimistic-concurrency-control.html) but I don't know what the `e4s` library I'm using does with it.

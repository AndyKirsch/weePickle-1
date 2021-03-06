## [Shading](https://github.com/rallyhealth/sbt-shading) + [SemVer](https://semver.org/) + [MiMa](https://github.com/lightbend/mima)

Many of Rally Health's libraries need to work with JSON. However, we have
found (through long painful experience) that letting them use Play-Json
puts us into a particular form of Dependency Hell: the libraries wind up
dependent on a *specific version* of Play-Json, which means that upgrading
Play requires upgrading much of our ecosystem, which is a hassle. We want
to decouple our libraries from Play as much as possible, to reduce this
friction.

So we are encouraging libraries to make use of weePickle instead: it's
popular, well-supported and fast.

However, if we allowed libraries to simply pick random versions of
uPickle, we'd be right back where we were with Play-Json: if different
libraries used different versions, we could wind up with evictions and
runtime collisions, since uPickle isn't shaded.

So this is a shaded fork of uPickle. It is hard-shaded (instead of using
sbt-assembly or something like that) because uPickle includes macros with
hard-coded paths, so automatic shading isn't likely to work correctly.

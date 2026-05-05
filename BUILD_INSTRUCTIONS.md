# Before release upsdate

Jorigin Geotools is cross dependant with both Geotools and JavaFX. The versioning of the modules is tied to the version number of Geotools and JavaFX. 
The following instructions are related to the set-up of the libraries versions before a release. 

## 1. Main project
Within the root ``pom.xml`` file:

1. Set `gt.version` property to the desired Geotools version (ex: `<gt.version>34.3</gt.version>`)

2. Set `jfx.version` property to the desired JavaFX version (ex: `<jfx.version>24</jfx.version>`)

2. Set the `version` with the same value as <gt.version> (ex: `<version>34.3</version>`)

Exemple of POM:

```maven
...
<groupId>org.jorigin</groupId>
<artifactId>geotools</artifactId>
<version>34.3</version>
<packaging>pom</packaging>
...
<properties>
	...
	<!-- Geotools properties -->
	<gt.version>34.3</gt.version>
	
	<!-- JavaFX properties -->
	<jfx.version>24</jfx.version>
	
	...
</properties>
...
```

## 2. Submodules
Within the ``pom.xml`` of the two sub-module gt-jfx and gt-examples, set the ``version`` within the ``parent`` section to the value of the ``version`` from global ``pom.xml`` (see 1.):

After the modifications, POM files should look like:
 
```maven
...
<parent>
	<groupId>org.jorigin</groupId>
	<artifactId>geotools</artifactId>
	<version>34.3</version>
</parent>
...
```

## 3. README.md
Within the REAMDE.md file:

1. Update geotools version to the desired one (section Global usage, point 1).

2. Update the JavaFX version to the desired one (section JavaFX use).

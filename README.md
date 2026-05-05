# JOrigin Geotools Extension

This project provides extensions for [Geotools GIS Library](http://www.geotools.org). 

## Usage

### General use
The JOrigin Geotools extension can be integrated to a Maven project by editing its POM file. If the gt-jfx module is needed, please follow also the [Usage JFX part](###javafx-use).

1. (Optionnal) Add a Geotools version property. This property enable to not duplicate version numbers for Geotools or its JOrigin extension (as JOrigin extension version has to be the same as Geotools version):
```maven
<properties>
...
	<!-- Geotools properties -->
	<gt.version>34.3</gt.version>
...
</properties>
``` 

2. Add the JOrigin maven depot:
```maven
<repositories>
...
	<!-- JOrigin repository -->
	<repository>
		<id>jorigin</id>
		<name>JOrigin Release Repository</name>
		<url>https://maven.jorigin.org/</url>
		<snapshots><enabled>false</enabled></snapshots>
		<releases><enabled>true</enabled></releases>
	</repository>
...
</repositories>
```

3. Add the desired dependencies:
```maven
<dependencies>

...
		<!-- Jorigin - Geotools -->
		<dependency>
			<groupId>org.jorigin</groupId>
			<artifactId>gt-jfx</artifactId>
			<version>${gt.version}</version>
		</dependency>

		<dependency>
			<groupId>org.jorigin</groupId>
			<artifactId>gt-ref-spatialreference</artifactId>
			<version>${gt.version}</version>
		</dependency>
...

</dependencies>
```

### JavaFX use
The [gt-jfx]() module enable to use Geotools viewing capabilities within a JavaFX environment. As the module aims at not fixing a specific JavaFX version, the use of gt-jfx needs to explicitely list the JavaFX dependencies. 
The gt-jfx needs at least JavaFX 17, it is so possible to use it from 17 to 24. Integrating the module to a Maven project can be done by editing its POM file as follows:

1. (Optionnal) Add a JavaFX version property. This property enable to not duplicate version numbers for JavaFX within the dependencies:
```maven
<properties>
...
	<!-- JavaFX version -->
	<jfx.version>24</jfx.version>
...
</properties>
``` 

2. Add the JavaFX dependencies (for the whole project) and the `gt-jfx` dependency:
```maven
<dependencies>

...
	<!-- JavaFX -->
	<dependency>
		<groupId>org.openjfx</groupId>
		<artifactId>javafx-controls</artifactId>
		<version>${jfx.version}</version>
	</dependency>

	<dependency>
		<groupId>org.openjfx</groupId>
		<artifactId>javafx-swing</artifactId>
		<version>${jfx.version}</version>
	</dependency>

	<!-- Jorigin - Geotools -->
	<dependency>
		<groupId>org.jorigin</groupId>
		<artifactId>gt-jfx</artifactId>
		<version>${gt.version}</version>
	</dependency>
...

</dependencies>
```
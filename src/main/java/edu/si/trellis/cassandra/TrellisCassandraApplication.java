package edu.si.trellis.cassandra;

import static com.datastax.driver.core.TypeCodec.bigint;
import static edu.si.trellis.cassandra.DatasetCodec.datasetCodec;
import static edu.si.trellis.cassandra.IRICodec.iriCodec;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trellisldp.api.BinaryService;
import org.trellisldp.api.EventService;
import org.trellisldp.api.IdentifierService;
import org.trellisldp.api.MementoService;
import org.trellisldp.api.NamespaceService;
import org.trellisldp.api.NoopNamespaceService;
import org.trellisldp.api.ResourceService;
import org.trellisldp.app.TrellisApplication;
import org.trellisldp.app.config.TrellisConfiguration;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Metadata;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.InvalidQueryException;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.extras.codecs.jdk8.InstantCodec;

import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.setup.Bootstrap;

public class TrellisCassandraApplication extends TrellisApplication {
	private static final Logger log = LoggerFactory.getLogger(TrellisCassandraApplication.class);
	
    private static final String CQL_KEYSPACE = "CREATE KEYSPACE IF NOT EXISTS Trellis"
        + " WITH replication = {'class':'SimpleStrategy', 'replication_factor':1};";
    private static final String CQL_Metadata = "CREATE TABLE IF NOT EXISTS Metadata"
        + " (identifier text PRIMARY KEY, interactionModel text, hasAcl boolean, binaryIdentifier text,"
        + " mimeType text, size bigint, parent text);";
    private static final String CQL_Mutabledata = "CREATE TABLE IF NOT EXISTS Mutabledata"
        + " (identifier text PRIMARY KEY, quads text);";
    private static final String CQL_Immutabledata = "CREATE TABLE IF NOT EXISTS Immutabledata"
        + " (identifier text PRIMARY KEY, quads text);";
    private static final String CQL_Binarydata = "CREATE TABLE IF NOT EXISTS Binarydata"
    	+ " (identifier text, chunk_index int, chunk blob,"
    	+ " PRIMARY KEY (identifier, chunk_index)) WITH CLUSTERING ORDER BY (chunk_index ASC);";

    private Session session = null;

    @Override
    public void initialize(final Bootstrap<TrellisConfiguration> bootstrap) {
      super.initialize(bootstrap);
      // Enable variable substitution with environment variables
      bootstrap.setConfigurationSourceProvider(
          new SubstitutingSourceProvider(bootstrap.getConfigurationSourceProvider(),
                                         new EnvironmentVariableSubstitutor(false))
      );
    }
    
    private Session getCassandraSession() {
    	if(this.session != null) {
    		return this.session;
    	}
    	Map<String, Object> extraConfig = config.any();
        Session session = null;
        boolean newKeyspace = false;
        try {
            session = connect(extraConfig, "Trellis");
        } catch(InvalidQueryException e) {  // No such keyspace
            createKeyspaceIfNotExists(extraConfig);
            newKeyspace = true;
            session = connect(extraConfig, "Trellis");
        }
        Metadata metadata = session.getCluster().getMetadata();
        log.info("Connecting to cluster: {}", metadata.getClusterName());
        log.info("with nodes: {}", metadata.getAllHosts());
        if(newKeyspace) {
        	log.info("Created the keyspace 'Trellis' and associated tables.");
        } else {
        	log.info("Using the existing 'Trellis' keyspace.");
        }
        this.session = session;
        return this.session;
    }

    @Override
    protected ResourceService buildResourceService(IdentifierService idService,
                    MementoService mementoService, EventService notificationService) {
        return new CassandraResourceService(getCassandraSession());
    }

    @Override
	protected BinaryService buildBinaryService(IdentifierService idService) {
    	Map<String, Object> extraConfig = config.any();
    	Boolean enabled = (Boolean)extraConfig.get("enableCassandraBinaryService");
		if( enabled.booleanValue() ) {
			return super.buildBinaryService(idService);
		} else {
			return new CassandraBinaryService(idService, getCassandraSession(), 1024*1024 );
		}
	}

	private Session connect(Map<String, Object> config) {
        return connect(config, null);
    }

    private Session connect(Map<String, Object> config, String keyspace) {
        String cassandraAddress = (String) config.get("cassandraAddress");
        Integer cassandraPort = (Integer) config.get("cassandraPort");
        Session result = null;
        int attempt = 1;
        while(result == null) {
            Cluster cluster = Cluster.builder().withoutJMXReporting().withoutMetrics().addContactPoint(cassandraAddress)
                              .withPort(cassandraPort).build();
            if(attempt == 1) {
                cluster.getConfiguration().getCodecRegistry()
                    .register(iriCodec, datasetCodec, bigint(), InstantCodec.instance);
            }
            try {
                if(keyspace == null) {
                    result = cluster.connect();
                } else {
                    result = cluster.connect(keyspace);
                }
            } catch(NoHostAvailableException e) {
                attempt++;
                log.warn("Cassandra hosts are not unavailable, waiting 5 seconds for attempt {}..", attempt);
                log.trace("Cassandra hosts unavailable", e);
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch(InterruptedException e1) {
                    throw new Error("Interrupted while attempting to connect to Cassandra.", e1);
                }
            }
        }
        if(attempt > 1) {
            log.warn("Cassandra connection established, after {} seconds and {} attempts.", attempt*5, attempt);
        }
        return result;
    }

    @Override
    protected NamespaceService buildNamespaceService() {
        return new NoopNamespaceService();
    }

    private void createKeyspaceIfNotExists(Map<String, Object> config) {
    	Boolean enabled = (Boolean)config.get("enableCassandraBinaryService");
        Session ks = connect(config);
        ks.execute(CQL_KEYSPACE);
        ks.close();
        Session inits = connect(config, "Trellis");
        inits.execute(CQL_Metadata);
        inits.execute(CQL_Mutabledata);
        inits.execute(CQL_Immutabledata);
        if(enabled) inits.execute(CQL_Binarydata);
        inits.close();
    }

    /**
     * The main entry point.
     *
     * @param args the argument list
     * @throws Exception if something goes horribly awry
     */
    public static void main(final String[] args) throws Exception {
        new TrellisCassandraApplication().run(args);
    }

}

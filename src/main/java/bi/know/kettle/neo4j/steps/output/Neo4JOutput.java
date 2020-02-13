package bi.know.kettle.neo4j.steps.output;

import bi.know.kettle.neo4j.shared.MetaStoreUtil;
import bi.know.kettle.neo4j.shared.NeoConnectionUtils;
import bi.know.kettle.neo4j.steps.BaseNeoStep;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.neo4j.driver.Result;
import org.neo4j.driver.summary.Notification;
import org.neo4j.driver.summary.ResultSummary;
import org.neo4j.kettle.core.GraphUsage;
import org.neo4j.kettle.core.data.GraphData;
import org.neo4j.kettle.core.data.GraphNodeData;
import org.neo4j.kettle.core.data.GraphPropertyData;
import org.neo4j.kettle.core.data.GraphPropertyDataType;
import org.neo4j.kettle.core.data.GraphRelationshipData;
import org.neo4j.kettle.model.GraphPropertyType;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleValueException;
import org.pentaho.di.core.row.RowDataUtil;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.util.StringUtil;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.metastore.api.exceptions.MetaStoreException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


public class Neo4JOutput extends BaseNeoStep implements StepInterface {
  private static Class<?> PKG = Neo4JOutput.class; // for i18n purposes, needed by Translator2!!
  private Neo4JOutputMeta meta;
  private Neo4JOutputData data;

  public Neo4JOutput( StepMeta s, StepDataInterface stepDataInterface, int c, TransMeta t, Trans dis ) {
    super( s, stepDataInterface, c, t, dis );
  }

  /**
   * TODO:
   * 1. option to do NODE CREATE/NODE UPDATE (merge default?)
   * 2. optional commit size
   * 3. option to return node id?
   */
  public boolean processRow( StepMetaInterface smi, StepDataInterface sdi ) throws KettleException {

    meta = (Neo4JOutputMeta) smi;
    data = (Neo4JOutputData) sdi;

    Object[] row = getRow();
    if ( row == null ) {
      setOutputDone();
      return false;
    }

    if ( first ) {
      first = false;

      data.outputRowMeta = getInputRowMeta().clone();
      meta.getFields( data.outputRowMeta, getStepname(), null, null, this, repository, metaStore );

      data.fieldNames = data.outputRowMeta.getFieldNames();
      data.fromNodePropIndexes = new int[ meta.getFromNodeProps().length ];
      data.fromNodePropTypes = new GraphPropertyType[ meta.getFromNodeProps().length ];
      for ( int i = 0; i < meta.getFromNodeProps().length; i++ ) {
        data.fromNodePropIndexes[ i ] = data.outputRowMeta.indexOfValue( meta.getFromNodeProps()[ i ] );
        if ( data.fromNodePropIndexes[ i ] < 0 ) {
          throw new KettleException( "From node: Unable to find field '" + meta.getFromNodeProps()[ i ] + "' for property name '" + meta.getFromNodePropNames()[ i ] + "'" );
        }
        data.fromNodePropTypes[ i ] = GraphPropertyType.parseCode( meta.getFromNodePropTypes()[ i ] );
      }
      data.fromNodeLabelIndexes = new int[ meta.getFromNodeLabels().length ];
      for ( int i = 0; i < meta.getFromNodeLabels().length; i++ ) {
        data.fromNodeLabelIndexes[ i ] = data.outputRowMeta.indexOfValue( meta.getFromNodeLabels()[ i ] );
        if ( data.fromNodeLabelIndexes[ i ] < 0 && StringUtils.isEmpty( meta.getFromNodeLabelValues()[ i ] ) ) {
          throw new KettleException( "From node : please provide either a static label value or a field name to determine the label" );
        }
      }
      data.toNodePropIndexes = new int[ meta.getToNodeProps().length ];
      data.toNodePropTypes = new GraphPropertyType[ meta.getToNodeProps().length ];
      for ( int i = 0; i < meta.getToNodeProps().length; i++ ) {
        data.toNodePropIndexes[ i ] = data.outputRowMeta.indexOfValue( meta.getToNodeProps()[ i ] );
        data.toNodePropTypes[ i ] = GraphPropertyType.parseCode( meta.getToNodePropTypes()[ i ] );
      }
      data.toNodeLabelIndexes = new int[ meta.getToNodeLabels().length ];
      for ( int i = 0; i < meta.getToNodeLabels().length; i++ ) {
        data.toNodeLabelIndexes[ i ] = data.outputRowMeta.indexOfValue( meta.getToNodeLabels()[ i ] );
        if ( data.toNodeLabelIndexes[ i ] < 0 && StringUtils.isEmpty( meta.getToNodeLabelValues()[ i ] ) ) {
          throw new KettleException( "To node : please provide either a static label value or a field name to determine the label" );
        }
      }
      data.relPropIndexes = new int[ meta.getRelProps().length ];
      data.relPropTypes = new GraphPropertyType[ meta.getRelProps().length ];
      for ( int i = 0; i < meta.getRelProps().length; i++ ) {
        data.relPropIndexes[ i ] = data.outputRowMeta.indexOfValue( meta.getRelProps()[ i ] );
        data.relPropTypes[ i ] = GraphPropertyType.parseCode( meta.getRelPropTypes()[ i ] );
      }
      data.relationshipIndex = data.outputRowMeta.indexOfValue( meta.getRelationship() );
      data.fromLabelValues = new String[ meta.getFromNodeLabelValues().length ];
      for ( int i = 0; i < meta.getFromNodeLabelValues().length; i++ ) {
        data.fromLabelValues[ i ] = environmentSubstitute( meta.getFromNodeLabelValues()[ i ] );
      }
      data.toLabelValues = new String[ meta.getToNodeLabelValues().length ];
      for ( int i = 0; i < meta.getToNodeLabelValues().length; i++ ) {
        data.toLabelValues[ i ] = environmentSubstitute( meta.getToNodeLabelValues()[ i ] );
      }
      data.relationshipLabelValue = environmentSubstitute( meta.getRelationshipValue() );

      data.unwindList = new ArrayList<>();

      data.dynamicFromLabels = determineDynamicLabels( meta.getFromNodeLabels() );
      data.dynamicToLabels = determineDynamicLabels( meta.getToNodeLabels() );
      data.dynamicRelLabel = StringUtils.isNotEmpty( meta.getRelationship() );

      data.previousToLabels = null;
      data.previousFromLabelsClause = null;
      data.previousToLabelsClause = null;
      data.previousRelationshipLabel = null;

      // Calculate the operation types
      //
      data.fromOperationType = OperationType.MERGE;
      data.toOperationType = OperationType.MERGE;
      data.relOperationType = OperationType.MERGE;
      if ( meta.isUsingCreate() ) {
        data.fromOperationType = OperationType.CREATE;
        data.toOperationType = OperationType.CREATE;
        data.relOperationType = OperationType.CREATE;
      }
      if ( meta.isOnlyCreatingRelationships() ) {
        data.fromOperationType = OperationType.MATCH;
        data.toOperationType = OperationType.MATCH;
        data.relOperationType = OperationType.CREATE;
      }
      // No 'From' Node activity?
      //
      if ( meta.getFromNodeLabels().length == 0 && meta.getFromNodeLabelValues().length == 0 ) {
        data.fromOperationType = OperationType.NONE;
      }
      // No 'To' Node activity?
      //
      if ( meta.getToNodeLabels().length == 0 && meta.getToNodeLabelValues().length == 0 ) {
        data.toOperationType = OperationType.NONE;
      }
      // No relationship activity?
      //
      if ( StringUtils.isEmpty( meta.getRelationship() ) && StringUtils.isEmpty( meta.getRelationshipValue() ) ) {
        data.relOperationType = OperationType.NONE;
      }

      // Create a session
      //
      if ( meta.isReturningGraph() ) {
        log.logBasic( "Writing to output graph field, not to Neo4j" );
      } else {
        data.session = data.neoConnection.getSession( log );

        // Create indexes for the primary properties of the From and To nodes
        //
        if ( meta.isCreatingIndexes() ) {
          try {
            createNodePropertyIndexes( meta, data, getInputRowMeta(), row );
          } catch ( KettleException e ) {
            log.logError( "Unable to create indexes", e );
            return false;
          }
        }
      }
    }

    if ( meta.isReturningGraph() ) {
      // Let the next steps handle writing to Neo4j
      //
      outputGraphValue( getInputRowMeta(), row );

    } else {

      boolean changedLabel = calculateLabelsAndDetectChanges( row );
      if ( changedLabel ) {
        emptyUnwindList( changedLabel );
      }

      // Add rows to the unwind list.  Just put all the properties from the nodes and relationship in there
      // This could lead to property name collisions so we prepend the properties in the list with alias and underscore
      //
      Map<String, Object> propsMap = new HashMap<>();

      if ( data.fromOperationType != OperationType.NONE ) {
        addPropertiesToMap( propsMap, "f", data.fromNodePropIndexes, getInputRowMeta(), row, meta.getFromNodePropNames(), data.fromNodePropTypes );
      }
      if ( data.toOperationType != OperationType.NONE ) {
        addPropertiesToMap( propsMap, "t", data.toNodePropIndexes, getInputRowMeta(), row, meta.getToNodePropNames(), data.toNodePropTypes );
      }
      if ( data.relOperationType != OperationType.NONE ) {
        addPropertiesToMap( propsMap, "r", data.relPropIndexes, getInputRowMeta(), row, meta.getRelPropNames(), data.relPropTypes );
      }
      data.unwindList.add( propsMap );

      if ( data.unwindList.size() >= data.batchSize ) {
        emptyUnwindList( changedLabel );
      }

      // Simply pass on the current row .
      //
      putRow( data.outputRowMeta, row );

      // Remember the previous labels
      //
      data.previousFromLabels = data.fromLabels;
      data.previousFromLabelsClause = data.fromLabelsClause;
      data.previousToLabels = data.toLabels;
      data.previousToLabelsClause = data.toLabelsClause;
      data.previousRelationshipLabel = data.relationshipLabel;
    }
    return true;
  }

  private void addPropertiesToMap( Map<String, Object> rowMap, String alias, int[] nodePropIndexes, RowMetaInterface rowMeta, Object[] row, String[] nodePropNames,
                                   GraphPropertyType[] propertyTypes )
    throws KettleValueException {

    // Add all the node properties for the current row to the rowMap
    //
    for ( int i = 0; i < nodePropIndexes.length; i++ ) {

      ValueMetaInterface valueMeta = rowMeta.getValueMeta( nodePropIndexes[ i ] );
      Object valueData = row[ nodePropIndexes[ i ] ];

      GraphPropertyType propertyType = propertyTypes[ i ];
      Object neoValue = propertyType.convertFromKettle( valueMeta, valueData );

      String propName = "p" + nodePropIndexes[ i ];
      rowMap.put( propName, neoValue );
    }
  }


  private void emptyUnwindList( boolean changedLabel ) throws KettleException {

    Map<String, Object> properties = Collections.singletonMap( "props", data.unwindList );

    if ( data.cypher == null || changedLabel ) {

      StringBuilder cypher = new StringBuilder();
      cypher.append( "UNWIND $props as pr " ).append( Const.CR );

      String fromLabelsClause;
      List<String> fromLabels;
      String toLabelsClause;
      List<String>toLabels;
      String relationshipLabel;
      if (changedLabel) {
        fromLabelsClause = data.previousFromLabelsClause;
        fromLabels = data.previousFromLabels;
        toLabelsClause = data.previousToLabelsClause;
        toLabels = data.previousToLabels;
        relationshipLabel = data.previousRelationshipLabel;
      } else {
        fromLabelsClause = data.fromLabelsClause;
        fromLabels = data.fromLabels;
        toLabelsClause = data.toLabelsClause;
        toLabels = data.toLabels;
        relationshipLabel = data.relationshipLabel;
      }

      // The cypher for the 'from' node:
      //
      boolean takePreviousFrom = data.dynamicFromLabels && changedLabel && data.previousFromLabelsClause != null;
      String fromLabelClause = takePreviousFrom ? data.previousFromLabelsClause : data.fromLabelsClause;
      String fromMatchClause = getMatchClause( meta.getFromNodePropNames(), meta.getFromNodePropPrimary(), data.fromNodePropIndexes, "f" );
      switch ( data.fromOperationType ) {
        case NONE:
          break;
        case CREATE:
          cypher
            .append( "CREATE( " )
            .append( fromLabelsClause )
            .append( " " )
            .append( fromMatchClause )
            .append( ") " )
            .append( Const.CR )
          ;
          String setClause = getSetClause( false, "f", meta.getFromNodePropNames(), meta.getFromNodePropPrimary(), data.fromNodePropIndexes );
          if ( StringUtils.isNotEmpty( setClause ) ) {
            cypher
              .append( setClause )
              .append( Const.CR )
            ;
          }
          updateUsageMap( fromLabels, GraphUsage.NODE_CREATE );
          break;
        case MERGE:
          cypher
            .append( "MERGE( " )
            .append( fromLabelsClause )
            .append( " " )
            .append( fromMatchClause )
            .append( ") " )
            .append( Const.CR )
          ;
          setClause = getSetClause( false, "f", meta.getFromNodePropNames(), meta.getFromNodePropPrimary(), data.fromNodePropIndexes );
          if ( StringUtils.isNotEmpty( setClause ) ) {
            cypher
              .append( setClause )
              .append( Const.CR )
            ;
          }
          updateUsageMap(fromLabels, GraphUsage.NODE_UPDATE );
          break;
        case MATCH:
          cypher
            .append( "MATCH( " )
            .append( fromLabelsClause )
            .append( " " )
            .append( fromMatchClause )
            .append( ") " )
            .append( Const.CR )
          ;
          updateUsageMap( fromLabels, GraphUsage.NODE_READ );
          break;
        default:
          throw new KettleException( "Unsupported operation type for the 'from' node: " + data.fromOperationType );
      }

      // The cypher for the 'to' node:
      //
      boolean takePreviousTo = data.dynamicToLabels && changedLabel;
      String toLabelsClause = takePreviousTo ? data.previousToLabelsClause : data.toLabelsClause;
      String toMatchClause = getMatchClause( meta.getToNodePropNames(), meta.getToNodePropPrimary(), data.toNodePropIndexes, "f" );
      switch ( data.toOperationType ) {
        case NONE:
          break;
        case CREATE:
          cypher
            .append( "CREATE( " )
            .append( toLabelsClause )
            .append( " " )
            .append( toMatchClause )
            .append( ") " )
            .append( Const.CR )
          ;
          String setClause = getSetClause( false, "t", meta.getToNodePropNames(), meta.getToNodePropPrimary(), data.toNodePropIndexes );
          if ( StringUtils.isNotEmpty( setClause ) ) {
            cypher
              .append( setClause )
              .append( Const.CR )
            ;
          }
          updateUsageMap( toLabels, GraphUsage.NODE_CREATE );
          break;
        case MERGE:
          cypher
            .append( "MERGE( " )
            .append( toLabelsClause )
            .append( " " )
            .append( toMatchClause )
            .append( ") " )
            .append( Const.CR )
          ;
          setClause = getSetClause( false, "t", meta.getToNodePropNames(), meta.getToNodePropPrimary(), data.toNodePropIndexes );
          if ( StringUtils.isNotEmpty( setClause ) ) {
            cypher
              .append( setClause )
              .append( Const.CR )
            ;
          }
          updateUsageMap( toLabels, GraphUsage.NODE_UPDATE );
          break;
        case MATCH:
          cypher
            .append( "MATCH( " )
            .append( toLabelsClause )
            .append( " " )
            .append( toMatchClause )
            .append( ") " )
            .append( Const.CR )
          ;
          updateUsageMap( toLabels, GraphUsage.NODE_READ );
          break;
        default:
          throw new KettleException( "Unsupported operation type for the 'to' node: " + data.toOperationType );
      }

      // The cypher for the relationship:
      //
      String relationshipSetClause = getSetClause( false, "r", meta.getRelPropNames(), new boolean[ meta.getRelPropNames().length ], data.relPropIndexes );
      switch ( data.relOperationType ) {
        case NONE:
          break;
        case MERGE:
          cypher
            .append( "MERGE (f)-[" )
            .append( "r:" )
            .append( relationshipLabel )
            .append( "]->(t) " )
            .append( Const.CR )
            .append( relationshipSetClause )
            .append( Const.CR )
          ;
          updateUsageMap( Arrays.asList( relationshipLabel ), GraphUsage.RELATIONSHIP_UPDATE );
          ;
          break;
        case CREATE:
          cypher
            .append( "CREATE (f)-[" )
            .append( "r:" )
            .append( data.previousRelationshipLabel )
            .append( "]->(t) " )
            .append( Const.CR )
            .append( getSetClause( false, "r", meta.getRelPropNames(), new boolean[ meta.getRelPropNames().length ], data.relPropIndexes ) )
            .append( Const.CR )
          ;
          updateUsageMap( Arrays.asList( data.previousRelationshipLabel ), GraphUsage.RELATIONSHIP_CREATE );
          break;
      }

      data.cypher = cypher.toString();
    }

    // OK now we have the cypher statement, we can execute it...
    //
    if ( isDebug() ) {
      logDebug( "Running Cypher: " + data.cypher );
      logDebug( "properties list size : " + data.unwindList.size() );
    }

    // Run it always without beginTransaction()...
    //
    Result result = data.session.writeTransaction( tx -> tx.run( data.cypher, properties ) );
    processSummary( result );

    setLinesOutput( getLinesOutput() + data.unwindList.size() );

    // Clear the list
    //
    data.unwindList.clear();
  }


  private String getMatchClause( String[] propertyNames, boolean[] propertyPrimary, int[] nodePropIndexes, String alias ) {
    StringBuilder clause = new StringBuilder();

    for ( int i = 0; i < propertyNames.length; i++ ) {
      if ( propertyPrimary[ i ] ) {
        if ( clause.length() > 0 ) {
          clause.append( ", " );
        }
        clause
          .append( propertyNames[ i ] )
          .append( ": pr.p" )
          .append( nodePropIndexes[ i ] )
        ;
      }
    }

    if ( clause.length() == 0 ) {
      return "";
    } else {
      return "{ " + clause + " }";
    }
  }

  private String getSetClause( boolean allProperties, String alias, String[] propertyNames, boolean[] propertyPrimary, int[] nodePropIndexes ) {
    StringBuilder clause = new StringBuilder();

    for ( int i = 0; i < propertyNames.length; i++ ) {
      if ( allProperties || !propertyPrimary[ i ] ) {
        if ( clause.length() > 0 ) {
          clause.append( ", " );
        }
        clause
          .append( alias )
          .append( "." )
          .append( propertyNames[ i ] )
          .append( "= pr.p" )
          .append( nodePropIndexes[ i ] )
        ;
      }
    }

    if ( clause.length() == 0 ) {
      return "";
    } else {
      return "SET " + clause;
    }
  }


  private boolean calculateLabelsAndDetectChanges( Object[] row ) throws KettleException {
    boolean changedLabel = false;

    if ( data.fromOperationType != OperationType.NONE ) {
      if ( data.fromLabelsClause == null || data.dynamicFromLabels ) {
        data.fromLabels = getNodeLabels( meta.getFromNodeLabels(), data.fromLabelValues, getInputRowMeta(), row, data.fromNodeLabelIndexes );
        data.fromLabelsClause = getLabels( "f", data.fromLabels );
      }
      if ( data.dynamicFromLabels && data.previousFromLabelsClause != null && data.fromLabelsClause != null ) {
        if ( !data.fromLabelsClause.equals( data.previousFromLabelsClause ) ) {
          changedLabel = true;
        }
      }
    }

    if ( data.toOperationType != OperationType.NONE ) {
      if ( data.toLabelsClause == null || data.dynamicToLabels ) {
        data.toLabels = getNodeLabels( meta.getToNodeLabels(), data.toLabelValues, getInputRowMeta(), row, data.toNodeLabelIndexes );
        data.toLabelsClause = getLabels( "t", data.toLabels );
      }
      if ( data.dynamicToLabels && data.previousToLabelsClause != null && data.toLabelsClause != null ) {
        if ( !data.toLabelsClause.equals( data.previousToLabelsClause ) ) {
          changedLabel = true;
        }
      }
    }

    if ( data.relOperationType != OperationType.NONE ) {
      if ( data.dynamicRelLabel ) {
        data.relationshipLabel = getInputRowMeta().getString( row, data.relationshipIndex );
      }
      if ( StringUtils.isEmpty( data.relationshipLabel ) && StringUtils.isNotEmpty( data.relationshipLabelValue ) ) {
        data.relationshipLabel = data.relationshipLabelValue;
      }
      if ( data.dynamicRelLabel && data.previousRelationshipLabel != null && data.relationshipLabel != null ) {
        if ( !data.relationshipLabel.equals( data.previousRelationshipLabel ) ) {
          changedLabel = true;
        }
      }
    }

    return changedLabel;
  }

  private boolean determineDynamicLabels( String[] nodeLabelFields ) {
    for ( String nodeLabelField : nodeLabelFields ) {
      if ( StringUtils.isNotEmpty( nodeLabelField ) ) {
        return true;
      }
    }
    return false;
  }

  private void outputGraphValue( RowMetaInterface rowMeta, Object[] row ) throws KettleException {

    try {

      GraphData graphData = new GraphData();
      graphData.setSourceTransformationName( getTransMeta().getName() );
      graphData.setSourceStepName( getStepMeta().getName() );

      GraphNodeData sourceNodeData = null;
      GraphNodeData targetNodeData = null;
      GraphRelationshipData relationshipData;

      if ( meta.getFromNodeProps().length > 0 ) {
        sourceNodeData = createGraphNodeData( rowMeta, row, meta.getFromNodeLabels(), data.fromLabelValues, data.fromNodeLabelIndexes,
          data.fromNodePropIndexes, meta.getFromNodePropNames(), meta.getFromNodePropPrimary(), "from" );
        if ( !meta.isOnlyCreatingRelationships() ) {
          graphData.getNodes().add( sourceNodeData );
        }
      }
      if ( meta.getToNodeProps().length > 0 ) {
        targetNodeData = createGraphNodeData( rowMeta, row, meta.getToNodeLabels(), data.toLabelValues, data.toNodeLabelIndexes,
          data.toNodePropIndexes, meta.getToNodePropNames(), meta.getToNodePropPrimary(), "to" );
        if ( !meta.isOnlyCreatingRelationships() ) {
          graphData.getNodes().add( targetNodeData );
        }
      }

      String relationshipLabel = null;
      if ( data.relationshipIndex >= 0 ) {
        relationshipLabel = getInputRowMeta().getString( row, data.relationshipIndex );
      }
      if ( StringUtil.isEmpty( relationshipLabel ) && StringUtils.isNotEmpty( data.relationshipLabelValue ) ) {
        relationshipLabel = data.relationshipLabelValue;
      }
      if ( sourceNodeData != null && targetNodeData != null && StringUtils.isNotEmpty( relationshipLabel ) ) {

        relationshipData = new GraphRelationshipData();
        relationshipData.setSourceNodeId( sourceNodeData.getId() );
        relationshipData.setTargetNodeId( targetNodeData.getId() );
        relationshipData.setLabel( relationshipLabel );
        relationshipData.setId( sourceNodeData.getId() + " -> " + targetNodeData.getId() );
        relationshipData.setPropertySetId( "relationship" );

        // Add relationship properties...
        //
        // Set the properties
        //
        for ( int i = 0; i < data.relPropIndexes.length; i++ ) {

          ValueMetaInterface valueMeta = rowMeta.getValueMeta( data.relPropIndexes[ i ] );
          Object valueData = row[ data.relPropIndexes[ i ] ];

          String propertyName = meta.getRelPropNames()[ i ];
          GraphPropertyDataType propertyType = GraphPropertyDataType.getTypeFromKettle( valueMeta );
          Object propertyNeoValue = propertyType.convertFromKettle( valueMeta, valueData );
          boolean propertyPrimary = false;

          relationshipData.getProperties().add(
            new GraphPropertyData( propertyName, propertyNeoValue, propertyType, propertyPrimary )
          );
        }

        graphData.getRelationships().add( relationshipData );
      }

      // Pass it forward...
      //
      Object[] outputRowData = RowDataUtil.createResizedCopy( row, data.outputRowMeta.size() );
      int startIndex = rowMeta.size();
      outputRowData[ rowMeta.size() ] = graphData;
      putRow( data.outputRowMeta, outputRowData );

    } catch ( Exception e ) {
      throw new KettleException( "Unable to calculate graph output value", e );
    }
  }

  private GraphNodeData createGraphNodeData( RowMetaInterface rowMeta, Object[] row, String[] nodeLabels, String[] nodeLabelValues, int[] nodeLabelIndexes,
                                             int[] nodePropIndexes, String[] nodePropNames, boolean[] nodePropPrimary, String propertySetId ) throws KettleException {
    GraphNodeData nodeData = new GraphNodeData();

    // The property set ID is simply either "Source" or "Target"
    //
    nodeData.setPropertySetId( propertySetId );


    // Set the label(s)
    //
    List<String> labels = getNodeLabels( nodeLabels, nodeLabelValues, rowMeta, row, nodeLabelIndexes );
    for ( String label : labels ) {
      nodeData.getLabels().add( label );
    }

    StringBuilder nodeId = new StringBuilder();

    // Set the properties
    //
    for ( int i = 0; i < nodePropIndexes.length; i++ ) {

      ValueMetaInterface valueMeta = rowMeta.getValueMeta( nodePropIndexes[ i ] );
      Object valueData = row[ nodePropIndexes[ i ] ];

      String propertyName = nodePropNames[ i ];
      GraphPropertyDataType propertyType = GraphPropertyDataType.getTypeFromKettle( valueMeta );
      Object propertyNeoValue = propertyType.convertFromKettle( valueMeta, valueData );
      boolean propertyPrimary = nodePropPrimary[ i ];

      nodeData.getProperties().add( new GraphPropertyData( propertyName, propertyNeoValue, propertyType, propertyPrimary ) );

      // Part of the key...
      if ( nodePropPrimary[ i ] ) {
        if ( nodeId.length() > 0 ) {
          nodeId.append( "-" );
        }
        nodeId.append( valueMeta.getString( valueData ) );
      }
    }

    if ( nodeId.length() > 0 ) {
      nodeData.setId( nodeId.toString() );
    }


    return nodeData;
  }

  public boolean init( StepMetaInterface smi, StepDataInterface sdi ) {
    meta = (Neo4JOutputMeta) smi;
    data = (Neo4JOutputData) sdi;

    if ( !meta.isReturningGraph() ) {

      // Connect to Neo4j using info metastore Neo4j Connection metadata
      //
      if ( StringUtils.isEmpty( meta.getConnection() ) ) {
        log.logError( "You need to specify a Neo4j connection to use in this step" );
        return false;
      }

      try {
        // To correct lazy programmers who built certain PDI steps...
        //
        data.metaStore = MetaStoreUtil.findMetaStore( this );
        data.neoConnection = NeoConnectionUtils.getConnectionFactory( data.metaStore ).loadElement( meta.getConnection() );
        data.neoConnection.initializeVariablesFrom( this );
        data.version4 = data.neoConnection.isVersion4();
      } catch ( MetaStoreException e ) {
        log.logError( "Could not gencsv Neo4j connection '" + meta.getConnection() + "' from the metastore", e );
        return false;
      }

      data.batchSize = Const.toLong( environmentSubstitute( meta.getBatchSize() ), 1 );
    }

    return super.init( smi, sdi );
  }

  public void dispose( StepMetaInterface smi, StepDataInterface sdi ) {
    data = (Neo4JOutputData) sdi;

    if ( !isStopped() ) {
      try {
        wrapUpTransaction();
      } catch ( KettleException e ) {
        logError( "Error wrapping up transaction", e );
        setErrors( 1L );
        stopAll();
      }
    }

    if ( data.session != null ) {
      data.session.close();
    }

    super.dispose( smi, sdi );
  }

  private String getLabels( String nodeAlias, List<String> nodeLabels ) {

    if ( nodeLabels.isEmpty() ) {
      return null;
    }

    StringBuilder labels = new StringBuilder( nodeAlias );
    for ( String nodeLabel : nodeLabels ) {
      labels.append( ":" );
      labels.append( escapeLabel( nodeLabel ) );
    }
    return labels.toString();
  }

  private void processSummary( Result result ) throws KettleException {
    boolean error = false;
    ResultSummary summary = result.consume();
    for ( Notification notification : summary.notifications() ) {
      log.logError( notification.title() + " (" + notification.severity() + ")" );
      log.logError( notification.code() + " : " + notification.description() + ", position " + notification.position() );
      error = true;
    }
    if ( error ) {
      throw new KettleException( "Error found while executing cypher statement(s)" );
    }
  }

  private String buildParameterClause( String parameterName ) {
    if ( data.version4 ) {
      return "$" + parameterName;
    } else {
      return "{" + parameterName + "}";
    }
  }

  private String generateMatchClause( String alias, String mapName, List<String> nodeLabels, String[] nodeProps, String[] nodePropNames,
                                      GraphPropertyType[] nodePropTypes,
                                      boolean[] nodePropPrimary,
                                      RowMetaInterface rowMeta, Object[] rowData, int[] nodePropIndexes,
                                      Map<String, Object> parameters, AtomicInteger paramNr ) throws KettleValueException {
    String matchClause = "(" + alias;
    for ( int i = 0; i < nodeLabels.size(); i++ ) {
      String label = escapeProp( nodeLabels.get( i ) );
      matchClause += ":" + label;
    }
    matchClause += " {";

    boolean firstProperty = true;
    for ( int i = 0; i < nodeProps.length; i++ ) {
      if ( nodePropPrimary[ i ] ) {
        if ( firstProperty ) {
          firstProperty = false;
        } else {
          matchClause += ", ";
        }
        String propName;
        if ( StringUtils.isNotEmpty( nodePropNames[ i ] ) ) {
          propName = nodePropNames[ i ];
        } else {
          propName = nodeProps[ i ];
        }
        String parameterName = "param" + paramNr.incrementAndGet();

        if ( mapName == null ) {
          matchClause += propName + " : " + buildParameterClause( parameterName );
        } else {
          matchClause += propName + " : " + mapName + "." + parameterName;
        }

        if ( parameters != null ) {
          ValueMetaInterface valueMeta = rowMeta.getValueMeta( nodePropIndexes[ i ] );
          Object valueData = rowData[ nodePropIndexes[ i ] ];

          GraphPropertyType propertyType = nodePropTypes[ i ];
          Object neoValue = propertyType.convertFromKettle( valueMeta, valueData );

          parameters.put( parameterName, neoValue );
        }
      }
    }
    matchClause += " })";

    return matchClause;
  }

  public List<String> getNodeLabels( String[] labelFields, String[] labelValues, RowMetaInterface rowMeta, Object[] rowData, int[] labelIndexes ) throws KettleValueException {
    List<String> labels = new ArrayList<>();

    for ( int a = 0; a < labelFields.length; a++ ) {
      String label = null;
      if ( StringUtils.isNotEmpty( labelFields[ a ] ) ) {
        label = rowMeta.getString( rowData, labelIndexes[ a ] );
      }
      if ( StringUtils.isEmpty( label ) && StringUtils.isNotEmpty( labelValues[ a ] ) ) {
        label = labelValues[ a ];
      }
      if ( StringUtils.isNotEmpty( label ) ) {
        labels.add( label );
      }
    }
    return labels;
  }

  public String escapeLabel( String str ) {
    if ( str.contains( " " ) || str.contains( "." ) ) {
      str = "`" + str + "`";
    }
    return str;
  }

  public String escapeProp( String str ) {
    return StringEscapeUtils.escapeJava( str );
  }

  private void createNodePropertyIndexes( Neo4JOutputMeta meta, Neo4JOutputData data, RowMetaInterface rowMeta, Object[] rowData )
    throws KettleException {

    // Only create indexes on the first copy
    //
    if ( getCopy() != 0 ) {
      return;
    }

    createIndexForNode( data, meta.getFromNodeLabels(), meta.getFromNodeLabelValues(), meta.getFromNodeProps(), meta.getFromNodePropNames(), meta.getFromNodePropPrimary(), rowMeta,
      rowData );
    createIndexForNode( data, meta.getToNodeLabels(), meta.getToNodeLabelValues(), meta.getToNodeProps(), meta.getToNodePropNames(), meta.getToNodePropPrimary(), rowMeta,
      rowData );

  }

  private void createIndexForNode( Neo4JOutputData data, String[] nodeLabelFields, String[] nodeLabelValues, String[] nodeProps, String[] nodePropNames, boolean[] nodePropPrimary,
                                   RowMetaInterface rowMeta, Object[] rowData )
    throws KettleValueException {

    // Which labels to index?
    //
    Set<String> labels = new HashSet<>();
    labels.addAll( Arrays.asList( nodeLabelValues ).stream().filter( s -> StringUtils.isNotEmpty( s ) ).collect( Collectors.toList() ) );

    for ( String nodeLabelField : nodeLabelFields ) {
      if ( StringUtils.isNotEmpty( nodeLabelField ) ) {
        String label = rowMeta.getString( rowData, nodeLabelField, null );
        if ( StringUtils.isNotEmpty( label ) ) {
          labels.add( label );
        }
      }
    }

    // Create a index on the primary fields of the node properties
    //
    for ( String label : labels ) {
      List<String> primaryProperties = new ArrayList<>();
      for ( int f = 0; f < nodeProps.length; f++ ) {
        if ( nodePropPrimary[ f ] ) {
          if ( StringUtils.isNotEmpty( nodePropNames[ f ] ) ) {
            primaryProperties.add( nodePropNames[ f ] );
          } else {
            primaryProperties.add( nodeProps[ f ] );
          }
        }
      }

      if ( label != null && primaryProperties.size() > 0 ) {
        NeoConnectionUtils.createNodeIndex( log, data.session, Collections.singletonList( label ), primaryProperties );
      }
    }
  }

  @Override public void batchComplete() throws KettleException {
    wrapUpTransaction();
  }

  private void wrapUpTransaction() throws KettleException {

    if ( !isStopped() ) {
      if ( data.unwindList != null && data.unwindList.size() > 0 ) {
        emptyUnwindList( true ); // force write!
      }
    }

    // Allow gc
    //
    data.unwindList = new ArrayList<>();
  }

  /**
   * Update the usagemap.  Add all the labels to the node usage.
   *
   * @param labels
   * @param usage
   */
  protected void updateUsageMap( List<String> labels, GraphUsage usage ) throws KettleValueException {

    if ( labels == null ) {
      return;
    }

    Map<String, Set<String>> stepsMap = data.usageMap.get( usage.name() );
    if ( stepsMap == null ) {
      stepsMap = new HashMap<>();
      data.usageMap.put( usage.name(), stepsMap );
    }

    Set<String> labelSet = stepsMap.get( getStepname() );
    if ( labelSet == null ) {
      labelSet = new HashSet<>();
      stepsMap.put( getStepname(), labelSet );
    }

    for ( String label : labels ) {
      if ( StringUtils.isNotEmpty( label ) ) {
        labelSet.add( label );
      }
    }
  }
}

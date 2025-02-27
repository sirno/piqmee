package piqmee.tree;

import beast.base.core.Description;
import beast.base.core.Input;
import beast.base.evolution.alignment.Alignment;
import beast.base.evolution.alignment.FilteredAlignment;
import beast.base.evolution.tree.ClusterTree;
import beast.base.evolution.tree.ClusterTree.*;
import beast.base.inference.StateNodeInitialiser;

import java.util.*;

/**
 * @author Veronika Boskova created on 09/03/2017.
 */

@Description("Class to initialize a QuasiSpeciesTree from the alignment only")
public class QuasiSpeciesClusterTree extends QuasiSpeciesTree implements StateNodeInitialiser {

    final public Input<Type> clusterTypeInput = new Input<>("clusterType",
            "type of clustering algorithm used for generating initial beast.tree. " +
                    "Should be one of " + Arrays.toString(Type.values()) + " (default " + Type.average + ")",
            Type.average, Type.values());
    public Input<Boolean> collapseIdenticalSequencesInput = new Input<>("collapseIdenticalSequences",
            "Should nodes that have identical sequences be collapsed to one haplotype? " +
                    "Default true.",
            true);

    public QuasiSpeciesClusterTree() {
    }

    @Override
    public void initAndValidate() {
        super.initAndValidate();

        // make sure to use date and haploCount traits
        if (m_initial.get() != null)
            processTraits(m_initial.get().m_traitList.get());
        else
            processTraits(m_traitList.get());

        // initialize the tree
        // get the input alignment
        Alignment data = dataInput.get();
        if (data instanceof FilteredAlignment) {
            data = ((FilteredAlignment) data).alignmentInput.get();
        }
        if (data == null)
            throw new RuntimeException("The data input needs to be specified");

        ClusterTree inputTree = new ClusterTree();
        inputTree.setDateTrait(timeTraitSet);
        inputTree.initByName(
                "clusterType", clusterTypeInput.get(),
                "taxa", data);

        // initialize the quasispecies tree - and collapse identical sequences, if
        // necessary
        if (haplotypeCountsSet != null && !haplotypeCountIsAll1(haplotypeCountsSet))
            initFromUniqueHaploTree(inputTree, data,
                    collapseIdenticalSequencesInput.get(), collapseSequencesWithMissingDataInput.get(),
                    haplotypeCountsSet);
        else
            initFromFullTree(inputTree, data,
                    collapseIdenticalSequencesInput.get(), collapseSequencesWithMissingDataInput.get());

        initStateNodes();
    }

    @Override
    public void initStateNodes() {
        if (m_initial.get() != null) {
            m_initial.get().assignFromWithoutID(this);
        }
    }

}

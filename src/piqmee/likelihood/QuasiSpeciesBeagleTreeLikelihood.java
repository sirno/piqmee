/*
 * TreeLikelihood.java
 *
 * Copyright (C) 2002-2006 Alexei Drummond and Andrew Rambaut
 *
 * This file is part of BEAST.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership and licensing.
 *
 * BEAST is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 *  BEAST is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BEAST; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin St, Fifth Floor,
 * Boston, MA  02110-1301  USA
 */

package piqmee.likelihood;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import beagle.Beagle;
import beagle.BeagleFactory;
import beagle.BeagleFlag;
import beagle.BeagleInfo;
import beagle.InstanceDetails;
import beagle.ResourceDetails;
import beast.core.CalculationNode;
import beast.core.Description;
import beast.core.util.Log;
import beast.evolution.alignment.Alignment;
import beast.evolution.branchratemodel.StrictClockModel;
import beast.evolution.sitemodel.SiteModel;
import beast.evolution.substitutionmodel.EigenDecomposition;
import beast.evolution.tree.Node;
import beast.evolution.tree.Tree;
import piqmee.tree.QuasiSpeciesNode;
import piqmee.tree.QuasiSpeciesTree;


/**
 * BeagleTreeLikelihoodModel - implements a Likelihood Function for sequences on a tree.
 *
 * @author Andrew Rambaut
 * @author Alexei Drummond
 * @author Marc Suchard
 * @version $Id$
 */

@Description("Uses Beagle library to calculate Quasi Species Tree likelihood")
public class QuasiSpeciesBeagleTreeLikelihood extends QuasiSpeciesTreeLikelihood2 {

	    // This property is a comma-delimited list of resource numbers (0 == CPU) to
	    // allocate each BEAGLE instance to. If less than the number of instances then
	    // will wrap around.
	    // note: to use a different device, say device 2, start beast with
	    // java -Dbeagle.resource.order=2 beast.app.BeastMCMC
	    private static final String RESOURCE_ORDER_PROPERTY = "beagle.resource.order";
	    private static final String PREFERRED_FLAGS_PROPERTY = "beagle.preferred.flags";
	    private static final String REQUIRED_FLAGS_PROPERTY = "beagle.required.flags";
	    private static final String SCALING_PROPERTY = "beagle.scaling";
	    private static final String RESCALE_FREQUENCY_PROPERTY = "beagle.rescale";
	    // Which scheme to use if choice not specified (or 'default' is selected):
	    private static final PartialsRescalingScheme DEFAULT_RESCALING_SCHEME = PartialsRescalingScheme.DYNAMIC;

	    private static int instanceCount = 0;
	    private static List<Integer> resourceOrder = null;
	    private static List<Integer> preferredOrder = null;
	    private static List<Integer> requiredOrder = null;
	    private static List<String> scalingOrder = null;

	    private static final int RESCALE_FREQUENCY = 10000;
	    private static final int RESCALE_TIMES = 1;

	    boolean m_bUseAmbiguities, m_bUseTipLikelihoods;
	    int nStates;
	    int m_nNodeCount;
	    
	    private double [] currentCategoryRates;
//	    private double [] storedCurrentCategoryRates;
	    private double [] currentFreqs;
	    private double [] currentCategoryWeights;
	    private double [] matrices;
	    
	    private int invariantCategory = -1;

	    @Override
	    public void initAndValidate() {
	        boolean forceJava = Boolean.valueOf(System.getProperty("java.only"));
	        if (forceJava) {
	        	return;
	        }
	        initialize();

	        rates = new double[nStates];
	        storedRates = new double [nStates];
	        getNoChangeRates(rates);
	    }

	    private boolean initialize() {
	        m_nNodeCount = treeInput.get().getNodeCount() * 2 + 2;
	        nodeCount = m_nNodeCount; 
	        m_bUseAmbiguities = useAmbiguities.get();
	        m_bUseTipLikelihoods = useTipLikelihoods.get();
	        if (!(siteModelInput.get() instanceof SiteModel.Base)) {
	        	throw new IllegalArgumentException("siteModel input should be of type SiteModel.Base");
	        }
	        siteModel = (SiteModel.Base) siteModelInput.get();
	        siteModel.setDataType(dataInput.get().getDataType());
	        substitutionModel = siteModel.substModelInput.get();
	        branchRateModel = branchRateModelInput.get();
	        if (branchRateModel == null) {
	        	branchRateModel = new StrictClockModel();
	        }
	        branchLengths = new double[m_nNodeCount];
	        storedBranchLengths = new double[m_nNodeCount];

	        nStates = dataInput.get().getMaxStateCount();
	        patternCount = dataInput.get().getPatternCount();

	        //System.err.println("Attempt to load BEAGLE TreeLikelihood");

	        eigenCount = 1;//this.branchSubstitutionModel.getEigenCount();

	        double[] categoryRates = siteModel.getCategoryRates(null);
	        // check for invariant rates category
	        if (siteModel.hasPropInvariantCategory) {
		        for (int i = 0; i < categoryRates.length; i++) {
		        	if (categoryRates[i] == 0) {
		        		proportionInvariant = siteModel.getRateForCategory(i, null);
		                int stateCount = dataInput.get().getMaxStateCount();
		                int patterns = dataInput.get().getPatternCount();
		                calcConstantPatternIndices(patterns, stateCount);
		                invariantCategory = i;
		                
		                double [] tmp = new double [categoryRates.length - 1];
		                for (int k = 0; k < invariantCategory; k++) {
		                	tmp[k] = categoryRates[k];
		                }
		                for (int k = invariantCategory + 1; k < categoryRates.length; k++) {
		                	tmp[k-1] = categoryRates[k];
		                }
		                categoryRates = tmp;
		        		break;
		        	}
		        }
		        if (constantPattern != null && constantPattern.size() > dataInput.get().getPatternCount()) {
		        	// if there are many more constant patterns than patterns (each pattern can
		        	// have a number of constant patters, one for each state) it is less efficient
		        	// to just calculate the TreeLikelihood for constant sites than optimising
		        	Log.debug("switch off constant sites optimisiation: calculating through separate TreeLikelihood category (as in the olden days)");
		        	invariantCategory = -1;
		        	proportionInvariant = 0;
		        	constantPattern = null;
		        	categoryRates = siteModel.getCategoryRates(null);
		        }
	        }        

	        this.categoryCount = siteModel.getCategoryCount() - (invariantCategory >= 0 ? 1 : 0);
	        tipCount = treeInput.get().getLeafNodeCount() * 2;
	        leafNodeCount = tipCount;
	        actualLeafNodeCount = treeInput.get().getLeafNodeCount();
	        
	        internalNodeCount = m_nNodeCount - tipCount;

	        int compactPartialsCount = tipCount;
	        if (m_bUseAmbiguities) {
	            // if we are using ambiguities then we don't use tip partials
	            compactPartialsCount = 0;
	        }

	        // one partials buffer for each tip and two for each internal node (for store restore)
	        partialBufferHelper = new BufferIndexHelper(m_nNodeCount, tipCount);

	        // two eigen buffers for each decomposition for store and restore.
	        eigenBufferHelper = new BufferIndexHelper(eigenCount, 0);

	        // two matrices for each node less the root
	        matrixBufferHelper = new BufferIndexHelper(m_nNodeCount, 0);

	        // one scaling buffer for each internal node plus an extra for the accumulation, then doubled for store/restore
	        scaleBufferHelper = new BufferIndexHelper(getScaleBufferCount(), 0);

	        // Attempt to get the resource order from the System Property
	        if (resourceOrder == null) {
	            resourceOrder = parseSystemPropertyIntegerArray(RESOURCE_ORDER_PROPERTY);
	        }
	        if (preferredOrder == null) {
	            preferredOrder = parseSystemPropertyIntegerArray(PREFERRED_FLAGS_PROPERTY);
	        }
	        if (requiredOrder == null) {
	            requiredOrder = parseSystemPropertyIntegerArray(REQUIRED_FLAGS_PROPERTY);
	        }
	        if (scalingOrder == null) {
	            scalingOrder = parseSystemPropertyStringArray(SCALING_PROPERTY);
	        }

	        // first set the rescaling scheme to use from the parser
	        rescalingScheme = PartialsRescalingScheme.DEFAULT;// = rescalingScheme;
	        rescalingScheme = DEFAULT_RESCALING_SCHEME;
	        int[] resourceList = null;
	        long preferenceFlags = 0;
	        long requirementFlags = 0;

	        if (scalingOrder.size() > 0) {
	            this.rescalingScheme = PartialsRescalingScheme.parseFromString(
	                    scalingOrder.get(instanceCount % scalingOrder.size()));
	        }

	        if (resourceOrder.size() > 0) {
	            // added the zero on the end so that a CPU is selected if requested resource fails
	            resourceList = new int[]{resourceOrder.get(instanceCount % resourceOrder.size()), 0};
	            if (resourceList[0] > 0) {
	                preferenceFlags |= BeagleFlag.PROCESSOR_GPU.getMask(); // Add preference weight against CPU
	            }
	        }

	        if (preferredOrder.size() > 0) {
	            preferenceFlags = preferredOrder.get(instanceCount % preferredOrder.size());
	        }

	        if (requiredOrder.size() > 0) {
	            requirementFlags = requiredOrder.get(instanceCount % requiredOrder.size());
	        }

	        if (scaling.get().equals(Scaling.always)) {
	        	this.rescalingScheme = PartialsRescalingScheme.ALWAYS;
	        }
	        if (scaling.get().equals(Scaling.none)) {
	        	this.rescalingScheme = PartialsRescalingScheme.NONE;
	        }
	        
	        // Define default behaviour here
	        if (this.rescalingScheme == PartialsRescalingScheme.DEFAULT) {
	            //if GPU: the default is^H^Hwas dynamic scaling in BEAST, now NONE
	            if (resourceList != null && resourceList[0] > 1) {
	                //this.rescalingScheme = PartialsRescalingScheme.DYNAMIC;
	                this.rescalingScheme = PartialsRescalingScheme.NONE;
	            } else { // if CPU: just run as fast as possible
	                //this.rescalingScheme = PartialsRescalingScheme.NONE;
	                // Dynamic should run as fast as none until first underflow
	                this.rescalingScheme = PartialsRescalingScheme.DYNAMIC;
	            }
	        }

	        if (this.rescalingScheme == PartialsRescalingScheme.AUTO) {
	            preferenceFlags |= BeagleFlag.SCALING_AUTO.getMask();
	            useAutoScaling = true;
	        } else {
//	                preferenceFlags |= BeagleFlag.SCALING_MANUAL.getMask();
	        }
	        String r = System.getProperty(RESCALE_FREQUENCY_PROPERTY);
	        if (r != null) {
	            rescalingFrequency = Integer.parseInt(r);
	            if (rescalingFrequency < 1) {
	                rescalingFrequency = RESCALE_FREQUENCY;
	            }
	        }

	        if (preferenceFlags == 0 && resourceList == null) { // else determine dataset characteristics
	            if (nStates == 4 && patternCount < 10000) // TODO determine good cut-off
	                preferenceFlags |= BeagleFlag.PROCESSOR_CPU.getMask();
	        }

	        if (substitutionModel.canReturnComplexDiagonalization()) {
	            requirementFlags |= BeagleFlag.EIGEN_COMPLEX.getMask();
	        }

	        instanceCount++;

	        try {
		        beagle = BeagleFactory.loadBeagleInstance(
		                tipCount,
		                partialBufferHelper.getBufferCount(),
		                compactPartialsCount,
		                nStates,
		                patternCount,
		                eigenBufferHelper.getBufferCount(),            // eigenBufferCount
		                matrixBufferHelper.getBufferCount(),
		                categoryCount,
		                scaleBufferHelper.getBufferCount(), // Always allocate; they may become necessary
		                resourceList,
		                preferenceFlags,
		                requirementFlags
		        );
	        } catch (Exception e) {
	        	beagle = null;
	        }
	        if (beagle == null) {
	            return false;
	        }

	        InstanceDetails instanceDetails = beagle.getDetails();
	        ResourceDetails resourceDetails = null;

	        if (instanceDetails != null) {
	            resourceDetails = BeagleFactory.getResourceDetails(instanceDetails.getResourceNumber());
	            if (resourceDetails != null) {
	                StringBuilder sb = new StringBuilder("  Using BEAGLE version: " + BeagleInfo.getVersion()
	                		+ " resource ");
	                sb.append(resourceDetails.getNumber()).append(": ");
	                sb.append(resourceDetails.getName()).append("\n");
	                if (resourceDetails.getDescription() != null) {
	                    String[] description = resourceDetails.getDescription().split("\\|");
	                    for (String desc : description) {
	                        if (desc.trim().length() > 0) {
	                            sb.append("    ").append(desc.trim()).append("\n");
	                        }
	                    }
	                }
	                sb.append("    with instance flags: ").append(instanceDetails.toString());
	                Log.info.println(sb.toString());
	            } else {
	                Log.warning.println("  Error retrieving BEAGLE resource for instance: " + instanceDetails.toString());
	                beagle = null;
	                return false;
	            }
	        } else {
	        	Log.warning.println("  No external BEAGLE resources available, or resource list/requirements not met, using Java implementation");
	            beagle = null;
	            return false;
	        }
	        Log.warning.println("  " + (m_bUseAmbiguities ? "Using" : "Ignoring") + " ambiguities in tree likelihood.");
	        Log.warning.println("  " + (m_bUseTipLikelihoods ? "Using" : "Ignoring") + " character uncertainty in tree likelihood.");
	        Log.warning.println("  With " + patternCount + " unique site patterns.");

	        
	        Node [] nodes = treeInput.get().getNodesAsArray();
	        for (int i = 0; i < nodes.length/2+1; i++) {
	        	int taxon = getTaxonIndex(nodes[i].getID(), dataInput.get());  
	            if (m_bUseAmbiguities || m_bUseTipLikelihoods) {
	                setPartials(beagle, i, taxon);
	            } else {
	                setStates(beagle, i, taxon);
	            }
	        }

	        if (dataInput.get().isAscertained) {
	            ascertainedSitePatterns = true;
	        }

	        double[] patternWeights = new double[patternCount];
	        for (int i = 0; i < patternCount; i++) {
	            patternWeights[i] = dataInput.get().getPatternWeight(i);
	        }
	        beagle.setPatternWeights(patternWeights);

	        if (this.rescalingScheme == PartialsRescalingScheme.AUTO &&
	                resourceDetails != null &&
	                (resourceDetails.getFlags() & BeagleFlag.SCALING_AUTO.getMask()) == 0) {
	            // If auto scaling in BEAGLE is not supported then do it here
	            this.rescalingScheme = PartialsRescalingScheme.DYNAMIC;
	            Log.warning.println("  Auto rescaling not supported in BEAGLE, using : " + this.rescalingScheme.getText());
	        } else {
	        	Log.warning.println("  Using rescaling scheme : " + this.rescalingScheme.getText());
	        }

	        if (this.rescalingScheme == PartialsRescalingScheme.DYNAMIC) {
	            everUnderflowed = false; // If false, BEAST does not rescale until first under-/over-flow.
	        }

	        updateSubstitutionModel = true;
	        updateSiteModel = true;
	        // some subst models (e.g. WAG) never become dirty, so set up subst models right now
	        setUpSubstModel();
	        // set up sitemodel
	        
	        beagle.setCategoryRates(categoryRates);
	        currentCategoryRates = categoryRates;
	        currentFreqs = new double[nStates];
	        currentCategoryWeights = new double[categoryRates.length];
	        
	        
	        // initialise probabilities as identity matrix
	        probabilities = new double[(nStates + 1) * (nStates + 1)];
	        Arrays.fill(probabilities, 1.0);

	        substitutionModel.getTransitionProbabilities(null, 0, 0, 1, probabilities);
	    	matrices = new double[nStates * nStates * categoryCount];
	    	for (int j = 0; j < categoryCount; j++) {
	    		System.arraycopy(probabilities, 0, matrices, nStates * nStates * j, nStates * nStates);
	    	}
	    	
	        for (int i = 0; i < tipCount; i++) {
	    		beagle.setTransitionMatrix(i, matrices, 1.0);
	        }
	        probabilities = new double[nStates * nStates];

	        return true;
	    }

	    
	    private static List<Integer> parseSystemPropertyIntegerArray(String propertyName) {
	        List<Integer> order = new ArrayList<>();
	        String r = System.getProperty(propertyName);
	        if (r != null) {
	            String[] parts = r.split(",");
	            for (String part : parts) {
	                try {
	                    int n = Integer.parseInt(part.trim());
	                    order.add(n);
	                } catch (NumberFormatException nfe) {
	                	Log.warning.println("Invalid entry '" + part + "' in " + propertyName);
	                }
	            }
	        }
	        return order;
	    }

	    private static List<String> parseSystemPropertyStringArray(String propertyName) {

	        List<String> order = new ArrayList<>();

	        String r = System.getProperty(propertyName);
	        if (r != null) {
	            String[] parts = r.split(",");
	            for (String part : parts) {
	                try {
	                    String s = part.trim();
	                    order.add(s);
	                } catch (NumberFormatException nfe) {
	                	Log.warning.println("Invalid getEigenDecompositionentry '" + part + "' in " + propertyName);
	                }
	            }
	        }
	        return order;
	    }
	    
	    
	    protected int getScaleBufferCount() {
	        return internalNodeCount + 1;
	    }

	    /**
	     * Sets the partials from a sequence in an alignment.
	     *
	     * @param beagle        beagle
	     * @param nodeIndex     nodeIndex
	     * @param taxon the taxon
	     */
	    protected final void setPartials(Beagle beagle,
	                                     int nodeIndex, int taxon) {
	        Alignment data = dataInput.get();

	        double[] partials = new double[patternCount * nStates * categoryCount];

	        int v = 0;
	        for (int i = 0; i < patternCount; i++) {

	        	double[] tipProbabilities = data.getTipLikelihoods(taxon,i);
	            if (tipProbabilities != null) {
	            	for (int state = 0; state < nStates; state++) {
	            		partials[v++] = tipProbabilities[state];
	            	}
	            }
	            else {
	            	int stateCount = data.getPattern(taxon, i);
	                boolean[] stateSet = data.getStateSet(stateCount);
	                for (int state = 0; state < nStates; state++) {
	                	 partials[v++] = (stateSet[state] ? 1.0 : 0.0);                
	                }
	            }
	        }

	        // if there is more than one category then replicate the partials for each
	        int n = patternCount * nStates;
	        int k = n;
	        for (int i = 1; i < categoryCount; i++) {
	            System.arraycopy(partials, 0, partials, k, n);
	            k += n;
	        }

	        beagle.setPartials(nodeIndex, partials);
	        beagle.setPartials(actualLeafNodeCount + nodeIndex, partials);
	    }

	    public int getPatternCount() {
	        return patternCount;
	    }

	    void setUpSubstModel() {
//	        // we are currently assuming a no-category model...
//	        // TODO More efficient to update only the substitution model that changed, instead of all
//	        for (int i = 0; i < eigenCount; i++) {
//	            //EigenDecomposition ed = m_substitutionModel.getEigenDecomposition(i, 0);
//	            EigenDecomposition ed = substitutionModel.getEigenDecomposition(null);
//
//	            eigenBufferHelper.flipOffset(i);
//
//	            beagle.setEigenDecomposition(
//	                    eigenBufferHelper.getOffsetIndex(i),
//	                    ed.getEigenVectors(),
//	                    ed.getInverseEigenVectors(),
//	                    ed.getEigenValues());
//	        }
	    }

	    /**
	     * Sets the partials from a sequence in an alignment.
	     *
	     * @param beagle        beagle
	     * @param nodeIndex     nodeIndex
	     * @param taxon         the taxon
	     */
	    protected final void setStates(Beagle beagle,
	                                   int nodeIndex, int taxon) {
	        Alignment data = dataInput.get();
	        int i;

	        int[] states = new int[patternCount];

	        for (i = 0; i < patternCount; i++) {
	            int code = data.getPattern(taxon, i);
	            int[] statesForCode = data.getDataType().getStatesForCode(code);
	            if (statesForCode.length==1)
	                states[i] = statesForCode[0];
	            else
	                states[i] = code; // Causes ambiguous states to be ignored.
	        }

	        beagle.setTipStates(nodeIndex, states);
	        beagle.setTipStates(treeInput.get().getLeafNodeCount() + nodeIndex, states);
	    }

	    /**
	     *
	     * @param taxon the taxon name as a string
	     * @param data the alignment
	     * @return the taxon index of the given taxon name for accessing its sequence data in the given alignment,
	     *         or -1 if the taxon is not in the alignment.
	     */
	    private int getTaxonIndex(String taxon, Alignment data) {    	
	        int taxonIndex = data.getTaxonIndex(taxon);
	        if (taxonIndex == -1) {
	        	if (taxon.startsWith("'") || taxon.startsWith("\"")) {
	                taxonIndex = data.getTaxonIndex(taxon.substring(1, taxon.length() - 1));
	            }
	            if (taxonIndex == -1) {
	            	throw new RuntimeException("Could not find sequence " + taxon + " in the alignment");
	            }
	        }
	        return taxonIndex;
		}
	    
	    
//	    public void setStates(int tipIndex, int[] states) {
//	        System.err.println("BTL:setStates");
//	        beagle.setTipStates(tipIndex, states);
//	        makeDirty();
//	    }
	//
//	    public void getStates(int tipIndex, int[] states) {
//	        System.err.println("BTL:getStates");
//	        beagle.getTipStates(tipIndex, states);
//	    }


	    /**
	     * check state for changed variables and update temp results if necessary *
	     */
	    @Override
	    protected boolean requiresRecalculation() {
	        hasDirt = Tree.IS_CLEAN;
	        
	        double[] categoryRates = siteModel.getCategoryRates(null);
	        if (constantPattern != null) {
	            double [] tmp = new double [categoryRates.length - 1];
	            for (int k = 0; k < invariantCategory; k++) {
	            	tmp[k] = categoryRates[k];
	            }
	            for (int k = invariantCategory + 1; k < categoryRates.length; k++) {
	            	tmp[k-1] = categoryRates[k];
	            }
	            categoryRates = tmp;
	        }
	        for (int i = 0; i < categoryRates.length; i++) {
	        	if (categoryRates[i] != currentCategoryRates[i]) {
	        		updateSiteModel = true;
	        		break;
	        	}
	        }
	        //updateSiteModel |= siteModel.isDirtyCalculation();

	        if (substitutionModel instanceof CalculationNode) {
	        	updateSubstitutionModel |= ((CalculationNode) substitutionModel).isDirtyCalculation();
	        }
	        
	        if (dataInput.get().isDirtyCalculation()) {
	            hasDirt = Tree.IS_FILTHY;
	            return true;
	        }
	        if (siteModel.isDirtyCalculation()) {
	            hasDirt = Tree.IS_DIRTY;
	            return true;
	        }
	        if (branchRateModel != null && branchRateModel.isDirtyCalculation()) {
	            //m_nHasDirt = Tree.IS_FILTHY;
	            return true;
	        }

	        return treeInput.get().somethingIsDirty();
	    }

	    /**
	     * Stores the additional state other than model components
	     */
	    @Override
	    public void store() {
	        partialBufferHelper.storeState();
	        eigenBufferHelper.storeState();
	        matrixBufferHelper.storeState();

	        if (useScaleFactors || useAutoScaling) { // Only store when actually used
	            scaleBufferHelper.storeState();
	            System.arraycopy(scaleBufferIndices, 0, storedScaleBufferIndices, 0, scaleBufferIndices.length);
//	            storedRescalingCount = rescalingCount;
	        }
	        super.store();
//          Done in super.store():	        
//	        System.arraycopy(branchLengths, 0, storedBranchLengths, 0, branchLengths.length);
//	        System.arraycopy(rates, 0, storedRates, 0, rates.length);
	    }

	    @Override
	    public void restore() {
	  		updateSiteModel = true; // this is required to upload the categoryRates to BEAGLE after the restore
	        
	        partialBufferHelper.restoreState();
	        eigenBufferHelper.restoreState();
	        matrixBufferHelper.restoreState();

	        if (useScaleFactors || useAutoScaling) {
	            scaleBufferHelper.restoreState();
	            int[] tmp2 = storedScaleBufferIndices;
	            storedScaleBufferIndices = scaleBufferIndices;
	            scaleBufferIndices = tmp2;
//	            rescalingCount = storedRescalingCount;
	        }

//	        updateRestrictedNodePartials = true;
	        super.restore();
	        //double[] tmp = m_branchLengths;
	        //m_branchLengths = storedBranchLengths;
	        //storedBranchLengths = tmp;
	    }

	    // **************************************************************
	    // Likelihood IMPLEMENTATION
	    // **************************************************************

	    /**
	     * Calculate the log likelihood of the current state.
	     *
	     * @return the log likelihood.
	     */
	    @Override
	    public double calculateLogP() {

	        if (siteModel.isDirtyCalculation())
	            getNoChangeRates(rates);

	        if (patternLogLikelihoods == null) {
	            patternLogLikelihoods = new double[patternCount];
	        }

	        if (matrixUpdateIndices == null) {
	            matrixUpdateIndices = new int[eigenCount][m_nNodeCount];
	            branchLengths = new double[m_nNodeCount];
	            storedBranchLengths = new double[m_nNodeCount];
	            branchUpdateCount = new int[eigenCount];
	            scaleBufferIndices = new int[internalNodeCount];
	            storedScaleBufferIndices = new int[internalNodeCount];
	        }

	        if (operations == null) {
	            operations = new int[1][internalNodeCount * Beagle.OPERATION_TUPLE_SIZE];
	            operationCount = new int[1];
	        }

	        recomputeScaleFactors = false;

	        if (this.rescalingScheme == PartialsRescalingScheme.ALWAYS) {
	            useScaleFactors = true;
	            recomputeScaleFactors = true;
	        } else if (this.rescalingScheme == PartialsRescalingScheme.DYNAMIC && everUnderflowed) {
	            useScaleFactors = true;
	            if (rescalingCountInner < RESCALE_TIMES) {
	                recomputeScaleFactors = true;
	                hasDirt = Tree.IS_FILTHY;// makeDirty();
//	                System.err.println("Recomputing scale factors");
	            }

	            rescalingCountInner++;
	            rescalingCount++;
	            if (rescalingCount > RESCALE_FREQUENCY) {
	                rescalingCount = 0;
	                rescalingCountInner = 0;
	            }
	        } else if (this.rescalingScheme == PartialsRescalingScheme.DELAYED && everUnderflowed) {
	            useScaleFactors = true;
	            recomputeScaleFactors = true;
	            hasDirt = Tree.IS_FILTHY;
	            rescalingCount++;
	        }

	        for (int i = 0; i < eigenCount; i++) {
	            branchUpdateCount[i] = 0;
	        }
	        operationListCount = 0;

	        operationCount[0] = 0;

	        final Node root = treeInput.get().getRoot();
	        traverse((QuasiSpeciesNode)root, null, true);

	        if (updateSubstitutionModel) {
	            setUpSubstModel();
	        }

	        if (updateSiteModel) {
	            double[] categoryRates = siteModel.getCategoryRates(null);
	            if (constantPattern != null) {
		            double [] tmp = new double [categoryRates.length - 1];
		            for (int k = 0; k < invariantCategory; k++) {
		            	tmp[k] = categoryRates[k];
		            }
		            for (int k = invariantCategory + 1; k < categoryRates.length; k++) {
		            	tmp[k-1] = categoryRates[k];
		            }
		            categoryRates = tmp;
		        }
	            for (int i = 0; i < categoryRates.length; i++) {
	            	if (categoryRates[i] != currentCategoryRates[i]) {
	                    beagle.setCategoryRates(categoryRates);
	                    i = categoryRates.length;
	            	}
	            }
	            currentCategoryRates = categoryRates;
	        }

//	        for (int i = 0; i < eigenCount; i++) {
//	            if (branchUpdateCount[0] > 0) {
//	                beagle.updateTransitionMatrices(
//	                        eigenBufferHelper.getOffsetIndex(0),
//	                        matrixUpdateIndices[0],
//	                        null,
//	                        null,
//	                        branchLengths,
//	                        branchUpdateCount[0]);
//	            }
//	        }

//	        if (COUNT_TOTAL_OPERATIONS) {
//	            for (int i = 0; i < eigenCount; i++) {
//	                totalMatrixUpdateCount += branchUpdateCount[i];
//	            }
//	            
//	            for (int i = 0; i <= numRestrictedPartials; i++) {
//	                totalOperationCount += operationCount[i];
//	            }
//	        }

	        double logL;
	        boolean done;
	        boolean firstRescaleAttempt = true;

	        do {

	            beagle.updatePartials(operations[0], operationCount[0], Beagle.NONE);

	            double[] categoryWeights = siteModel.getCategoryProportions(null);
	            if (constantPattern != null) {
		            double [] tmp = new double [categoryWeights.length - 1];
		            for (int k = 0; k < invariantCategory; k++) {
		            	tmp[k] = categoryWeights[k];
		            }
		            for (int k = invariantCategory + 1; k < categoryWeights.length; k++) {
		            	tmp[k-1] = categoryWeights[k];
		            }
		            categoryWeights = tmp;
	            }
	            double[] frequencies = substitutionModel.getFrequencies();

	            int cumulateScaleBufferIndex = Beagle.NONE;
	            if (useScaleFactors) {

	                if (recomputeScaleFactors) {
	                    scaleBufferHelper.flipOffset(internalNodeCount);
	                    cumulateScaleBufferIndex = scaleBufferHelper.getOffsetIndex(internalNodeCount);
	                    beagle.resetScaleFactors(cumulateScaleBufferIndex);
	                    beagle.accumulateScaleFactors(scaleBufferIndices, internalNodeCount, cumulateScaleBufferIndex);
	                } else {
	                    cumulateScaleBufferIndex = scaleBufferHelper.getOffsetIndex(internalNodeCount);
	                }
	            } else if (useAutoScaling) {
	                beagle.accumulateScaleFactors(scaleBufferIndices, internalNodeCount, Beagle.NONE);
	            }

	            // these could be set only when they change but store/restore would need to be considered
	            
	            for (int i = 0; i < categoryWeights.length; i++) {
	            	if (categoryWeights[i] != currentCategoryWeights[i]) {
	                    beagle.setCategoryWeights(0, categoryWeights);
	            		i = categoryWeights.length;
	            	}
	            }
	            currentCategoryWeights = categoryWeights;
	            
	            for (int i = 0; i < frequencies.length; i++) {
	            	if (frequencies[i] != currentFreqs[i]) {
	                    beagle.setStateFrequencies(0, frequencies);
	            		i = frequencies.length;
	            	}
	            }
	            currentFreqs = frequencies;

	            double[] sumLogLikelihoods = new double[1];

	            int rootIndex = leafNodeCount + root.getNr();
	            beagle.calculateRootLogLikelihoods(new int[]{partialBufferHelper.getOffsetIndex(rootIndex)}, new int[]{0}, new int[]{0},
	                    new int[]{cumulateScaleBufferIndex}, 1, sumLogLikelihoods);

	            logL = sumLogLikelihoods[0];
	            	            
	            if (ascertainedSitePatterns) {
	                // Need to correct for ascertainedSitePatterns
	                beagle.getSiteLogLikelihoods(patternLogLikelihoods);
	                logL = getAscertainmentCorrectedLogLikelihood(dataInput.get(),
	                        patternLogLikelihoods, dataInput.get().getWeights(), frequencies);
	            } else if (invariantCategory >= 0) {
	                beagle.getSiteLogLikelihoods(patternLogLikelihoods);
	                int [] patternWeights = dataInput.get().getWeights();
	                proportionInvariant = siteModel.getProportionInvariant();
	                
	                
	    	        for (int k : constantPattern) {
	    	        	int i = k / nStates;
	    	        	int j = k % nStates;
	    	        	patternLogLikelihoods[i] = (Math.log(Math.exp(patternLogLikelihoods[i]) + proportionInvariant * frequencies[j]));
	    	        }
	        	
		            logL = 0.0;
		            for (int i = 0; i < patternCount; i++) {
		                logL += patternLogLikelihoods[i] * patternWeights[i];
		            }
	            }

	            if (Double.isNaN(logL) || Double.isInfinite(logL)) {
	                everUnderflowed = true;
	                logL = Double.NEGATIVE_INFINITY;

	                if (firstRescaleAttempt && (rescalingScheme == PartialsRescalingScheme.DYNAMIC || rescalingScheme == PartialsRescalingScheme.DELAYED)) {
	                    // we have had a potential under/over flow so attempt a rescaling                	
	                	useScaleFactors = true;
	                    recomputeScaleFactors = true;

	                    for (int i = 0; i < eigenCount; i++) {
	                        branchUpdateCount[i] = 0;
	                    }

	                    operationCount[0] = 0;

	                    // traverse again but without flipping partials indices as we
	                    // just want to overwrite the last attempt. We will flip the
	                    // scale buffer indices though as we are recomputing them.
	                    traverse((QuasiSpeciesNode)root, null, false);

	                    done = false; // Run through do-while loop again
	                    firstRescaleAttempt = false; // Only try to rescale once
	                } else {
	                    // we have already tried a rescale, not rescaling or always rescaling
	                    // so just return the likelihood...
	                    done = true;
	                }
	            } else {
	                done = true; // No under-/over-flow, then done
	            }

	        } while (!done);

	        // If these are needed...
	        //beagle.getSiteLogLikelihoods(patternLogLikelihoods);

	        //********************************************************************
	        // after traverse all nodes and patterns have been updated --
	        //so change flags to reflect this.
//	        for (int i = 0; i < m_nNodeCount; i++) {
//	            updateNode[i] = false;
//	        }

	        updateSubstitutionModel = false;
	        updateSiteModel = false;
	        //********************************************************************

	        logP = logL;
	        return logL;
	    }

//	    protected void getPartials(int number, double[] partials) {
//	        int cumulativeBufferIndex = Beagle.NONE;
//	        /* No need to rescale partials */
//	        beagle.getPartials(partialBufferHelper.getOffsetIndex(number), cumulativeBufferIndex, partials);
//	    }

	    protected void setPartials(int number, double[] partials) {
	        beagle.setPartials(partialBufferHelper.getOffsetIndex(number), partials);
	    }

	    private double getAscertainmentCorrectedLogLikelihood(Alignment patternList,
	                                                          double[] patternLogLikelihoods,
	                                                          int[] patternWeights,
	                                                          double [] frequencies) {
	    	if (constantPattern != null) {
		        proportionInvariant = siteModel.getProportionInvariant();
		        for (int k : constantPattern) {
		        	int i = k / nStates;
		        	int j = k % nStates;
		        	patternLogLikelihoods[i] = (Math.log(Math.exp(patternLogLikelihoods[i]) + proportionInvariant * frequencies[j]));
		        }
	    	}
	    	
	        double logL = 0.0;
	        double ascertainmentCorrection = patternList.getAscertainmentCorrection(patternLogLikelihoods);
	        for (int i = 0; i < patternCount; i++) {
	            logL += (patternLogLikelihoods[i] - ascertainmentCorrection) * patternWeights[i];
	        }
	        return logL;
	    }

	    /**
	     * Traverse the tree calculating partial likelihoods.
	     *
	     * @param node           node
	     * @param operatorNumber operatorNumber
	     * @param flip           flip
	     * @return boolean
	     */
	    /* Assumes there IS a branch rate model as opposed to traverse() */
	    int traverse(final QuasiSpeciesNode node, int[] operatorNumber, boolean flip) {

	        QuasiSpeciesTree tree = (QuasiSpeciesTree) treeInput.get();

	        if (operatorNumber != null) {
	            operatorNumber[0] = -1;
	        }

	        int update = (node.isDirty() | hasDirt);

	        final int nodeIndex = node.getNr();

	        final double branchRate = branchRateModel.getRateForBranch(node);


	        // get the branch length, if the node is a tip, the total branch length above is the sum of the
	        // branch lengths from the origin/attachment time to tip
	        final double totalBranchTime;
	        if (node.isLeaf())
	            totalBranchTime = node.getTotalBranchLengths();
	        else if (node.isRoot())
//	            totalBranchTime = originHeight - node.getHeight();
	            totalBranchTime = 0.0;
	        else
	            totalBranchTime = node.getLengthWithoutHaplo();

	        final double branchTime =  totalBranchTime * branchRate;

	        // also check if the haplotype starts just above the node
	        //  if YES, then have to split the branch into part that evolves normally and a part that does not evolve
	        //  Note that we create a new variable since the QS can also start directly above the tip and we want the
	        //    tip node to hold the probability of no change in the QS sequence for the whole duration of the QS
	        //  For the relaxed clock model, this also means we need to create a new "branch rate category" for when haplo
	        //    starts just above the tip by splitting the rate for the branch before and after the the haplo start

	        // Update the transition probability for the partial branches (internal node to QS start)
	        int haploNr = node.getHaploAboveName();
	        if (haploNr != -1) {
	            double firstBranchingTime = ((QuasiSpeciesNode) tree.getNode(haploNr)).getAttachmentTimesList()[0];
	            toyNode.setNr(actualLeafNodeCount * 2 - 1 + haploNr);
	            double partBranchRate = 0.0;
	            double partBranchTime = 0.0;
	            if (node.isRoot()){
	                partBranchRate = 1;
	                partBranchTime = (node.getLength() - (firstBranchingTime - node.getHeight())) * partBranchRate;
	            } else {
	            	// TODO: verify that this is the right branch time (as logged by relaxed clock logger)
	                partBranchRate = branchRateModel.getRateForBranch(toyNode);
	                partBranchTime = (node.getLength() - (firstBranchingTime - node.getHeight())) * partBranchRate;
	            }
	            if (update != Tree.IS_CLEAN || partBranchTime != branchLengths[tipCount + haploNr]) {
	                branchLengths[tipCount + haploNr] = partBranchTime;
	                final Node parent = node.getParent();
	                if (flip) {
	                    // first flip the matrixBufferHelper
	                    matrixBufferHelper.flipOffset(tipCount + haploNr);
	                }
	                
	                for (int i = 0; i < siteModel.getCategoryCount(); i++) {
	                    final double jointBranchRate = siteModel.getRateForCategory(i, toyNode) * partBranchRate;
	                    if (parent != null)
	                        substitutionModel.getTransitionProbabilities(null, parent.getHeight(), firstBranchingTime, jointBranchRate, probabilities);
	                    else
	                        substitutionModel.getTransitionProbabilities(null, firstBranchingTime, firstBranchingTime, jointBranchRate, probabilities);
	            		System.arraycopy(probabilities, 0, matrices, probabilities.length * i, probabilities.length);
	                }
	                
	        		setTransitionMatrix(matrixBufferHelper.getOffsetIndex(tipCount + haploNr), matrices, 1.0);

	                update |= Tree.IS_DIRTY;
	            }
	        }
	        // First update the transition probability matrix(ices) for this branch
	        // Update the transition probability for the branches that do not evolve
	        // if the node is at tip, it holds the probability that the sequence does not change from the tip to the start of the haplo
	        if (node.isLeaf() && (update != Tree.IS_CLEAN  || branchTime != branchLengths[nodeIndex])){
	        	// TODO: verify that we have the right branch time (as logged by relaxed clock logger)
	            branchLengths[nodeIndex] = branchTime;
	            if (flip) {
	                // first flip the matrixBufferHelper
	                matrixBufferHelper.flipOffset(nodeIndex);
	            }
	            for (int i = 0; i < siteModel.getCategoryCount(); i++) {
	                final double jointBranchRate = siteModel.getRateForCategory(i, node) * branchRate;
	                // fill the transition probability matrix with move probabilities
	                Arrays.fill(probabilities, 0);
	                for (int j = 0; j < nStates; j++) {
	                    probabilities[j * (nStates + 1)] = Math.exp(totalBranchTime * jointBranchRate * rates[j]);
	                }
	        		System.arraycopy(probabilities, 0, matrices, probabilities.length * i, probabilities.length);
	            }
	    		setTransitionMatrix(matrixBufferHelper.getOffsetIndex(nodeIndex), matrices, 1.0);
	            update |= Tree.IS_DIRTY;
	            
	            // this sets node partials at top of leaf clade at partial[nodeIndex]
	        	calculatePartials(nodeIndex, actualLeafNodeCount + nodeIndex, tipCount + nodeIndex, flip);
	        }
	        //Update the transition probability matrix(ices) for all other branches
	        //if (!node.isRoot() && (update != Tree.IS_CLEAN || branchTime != m_StoredBranchLengths[nodeIndex])) {
	        else if (!node.isRoot() && !node.isLeaf() && (update != Tree.IS_CLEAN || branchTime != branchLengths[tipCount + nodeIndex])) {
	            branchLengths[tipCount + nodeIndex] = branchTime;
	            final Node parent = node.getParent();
	            if (flip) {
	                // first flip the matrixBufferHelper
	                matrixBufferHelper.flipOffset(tipCount + nodeIndex);
	            }
	            for (int i = 0; i < siteModel.getCategoryCount(); i++) {
	                final double jointBranchRate = siteModel.getRateForCategory(i, node) * branchRate;
	                substitutionModel.getTransitionProbabilities(node, parent.getHeight(), node.getHeight(), jointBranchRate, probabilities);
	        		System.arraycopy(probabilities, 0, matrices, probabilities.length * i, probabilities.length);
	            }
	    		setTransitionMatrix(matrixBufferHelper.getOffsetIndex(tipCount + nodeIndex), matrices, 1.0);
	            update |= Tree.IS_DIRTY;
	        }
	        //Update the transition probability matrix(ices) for root-origin branch
	        else if (node.isRoot() && (update != Tree.IS_CLEAN || branchTime != branchLengths[tipCount + nodeIndex])) {
	            branchLengths[tipCount + nodeIndex] = branchTime;
	            if (flip) {
	                // first flip the matrixBufferHelper
	                matrixBufferHelper.flipOffset(tipCount + nodeIndex);
	            }
	            for (int i = 0; i < siteModel.getCategoryCount(); i++) {
	                final double jointBranchRate = siteModel.getRateForCategory(i, node) * branchRate;
	                substitutionModel.getTransitionProbabilities(node, node.getHeight(), node.getHeight(), jointBranchRate, probabilities);
	        		System.arraycopy(probabilities, 0, matrices, probabilities.length * i, probabilities.length);
	            }
	    		setTransitionMatrix(matrixBufferHelper.getOffsetIndex(tipCount + nodeIndex), matrices, 1.0);
	            update |= Tree.IS_DIRTY;
	        }


	        // If the node is internal, update the partial likelihoods.
	        if (!node.isLeaf()) {

	            // Traverse down the two child nodes
	            final Node child1 = node.getLeft(); //Two children
	            final int update1 = traverse((QuasiSpeciesNode) child1, operatorNumber, flip);

	            final Node child2 = node.getRight();
	            final int update2 = traverse((QuasiSpeciesNode) child2, operatorNumber, flip);

	            // If either child node was updated then update this node too
	            if (update1 != Tree.IS_CLEAN || update2 != Tree.IS_CLEAN || update != Tree.IS_CLEAN) {

	                final int childNum1 = child1.getNr();
	                final int child1QS = ((QuasiSpeciesNode) child1).getContinuingHaploName();
	                final int child1parentQS = ((QuasiSpeciesNode) child1.getParent()).getContinuingHaploName();
	                final int childNum2 = child2.getNr();
	                final int child2QS = ((QuasiSpeciesNode) child2).getContinuingHaploName();
	                final int child2parentQS = ((QuasiSpeciesNode) child2.getParent()).getContinuingHaploName();
	                if (child1parentQS != child2parentQS)
	                    throw new IllegalStateException("In QuasiSpeciesTreeLikelihood - QS of parent of child 1 ne to QS of parent of child 2");

	                update |= (update1 | update2);

	                if (siteModel.integrateAcrossCategories()) {
	                    calculatePartials(tipCount + childNum1, tipCount + childNum2, tipCount + nodeIndex, flip);
	                } else {
	                    throw new RuntimeException("Error TreeLikelihood 632: Site categories not supported");
	                }

//	                if (node.isRoot()) {
//	                    // ((QuasieSpeciesBeerLikelihoodCore)likelihoodCore).calculateOriginRootPartials(nodeIndex, child1parentQS, nodeCount, rootPartials);
	//
//	                    // integrate over all possible site categories the sites can be in
//	                    final double[] proportions = siteModel.getCategoryProportions(node);
//	                    beagle.integratePartials(node.getNr(), proportions, originPartials);
	//
//	                    if (constantPattern != null) { // && !SiteModel.g_bUseOriginal) {
//	                        proportionInvariant = siteModel.getProportionInvariant();
//	                        // some portion of sites is invariant, so adjust root partials for this
//	                        for (final int i : constantPattern) {
//	                            originPartials[i] += proportionInvariant;
//	                        }
//	                    }
	//
//	                    // calculate the pattern likelihoods
//	                    // integrate over all possible starting state
//	                    final double[] frequencies = //m_pFreqs.get().
//	                            substitutionModel.getFrequencies();
//	                    beagle.calculateLogLikelihoods(originPartials, frequencies, patternLogLikelihoods);
//	                }
	            }
	        }
	        return update;
	    }

	    private void setTransitionMatrix(int offsetIndex, double[] matrices, double d) {
	    	// System.err.println("setTransitionMatrix: " + offsetIndex + " " + Arrays.toString(matrices));
	    	
			beagle.setTransitionMatrix(offsetIndex, matrices, d);
			
		}

		private void calculatePartials(int child1, int child2, int nodeNum, boolean flip) {
//	    	System.err.print("calculatePartials " + child1 + " " + matrixBufferHelper.getOffsetIndex(child1) + 
//	    			" " + child2 + " " + matrixBufferHelper.getOffsetIndex(child2) +
//	    			" " + nodeNum);
	        int x = operationCount[operationListCount] * Beagle.OPERATION_TUPLE_SIZE;

	        if (flip) {
	            // first flip the partialBufferHelper
	            partialBufferHelper.flipOffset(nodeNum);
	        }

	        final int[] operations = this.operations[operationListCount];

	        operations[x] = partialBufferHelper.getOffsetIndex(nodeNum);

	        if (useScaleFactors) {
	            // get the index of this scaling buffer
	            int n = nodeNum - tipCount;

	            if (recomputeScaleFactors) {
	                // flip the indicator: can take either n or (internalNodeCount + 1) - n
	                scaleBufferHelper.flipOffset(n);

	                // store the index
	                scaleBufferIndices[n] = scaleBufferHelper.getOffsetIndex(n);

	                operations[x + 1] = scaleBufferIndices[n]; // Write new scaleFactor
	                operations[x + 2] = Beagle.NONE;

	            } else {
	                operations[x + 1] = Beagle.NONE;
	                operations[x + 2] = scaleBufferIndices[n]; // Read existing scaleFactor
	            }

	        } else {

	            if (useAutoScaling) {
	                scaleBufferIndices[nodeNum - tipCount] = partialBufferHelper.getOffsetIndex(nodeNum);
	            }
	            operations[x + 1] = Beagle.NONE; // Not using scaleFactors
	            operations[x + 2] = Beagle.NONE;
	        }

	        operations[x + 3] = partialBufferHelper.getOffsetIndex(child1); // source node 1
	        operations[x + 4] = matrixBufferHelper.getOffsetIndex(child1); // source matrix 1
	        operations[x + 5] = partialBufferHelper.getOffsetIndex(child2); // source node 2
	        operations[x + 6] = matrixBufferHelper.getOffsetIndex(child2); // source matrix 2
//	        System.err.println(" => " + operations[x+3] + " " + operations[x+4] + " "
//	        		 + operations[x+5] + " " + operations[x+6] + " " 
//	        		 + operations[x+7]);
	        operationCount[operationListCount]++;
		}


	    // **************************************************************
	    // INSTANCE VARIABLES
	    // **************************************************************

	    private int eigenCount;
	    private int[][] matrixUpdateIndices;
	    //private double[][] branchLengths;
	    private int[] branchUpdateCount;
	    private int[] scaleBufferIndices;
	    private int[] storedScaleBufferIndices;

	    private int[][] operations;
	    private int operationListCount;
	    private int[] operationCount;

	    protected BufferIndexHelper partialBufferHelper;
	    public BufferIndexHelper getPartialBufferHelper() {return partialBufferHelper;}
	    
	    private /*final*/ BufferIndexHelper eigenBufferHelper;
	    protected BufferIndexHelper matrixBufferHelper;
	    protected BufferIndexHelper scaleBufferHelper;

	    protected /*final*/ int tipCount;
	    protected int actualLeafNodeCount;
	    protected /*final*/ int internalNodeCount;
	    protected /*final*/ int patternCount;

	    private PartialsRescalingScheme rescalingScheme = DEFAULT_RESCALING_SCHEME;
	    private int rescalingFrequency = RESCALE_FREQUENCY;
	    protected boolean useScaleFactors = false;
	    private boolean useAutoScaling = false;
	    private boolean recomputeScaleFactors = false;
	    private boolean everUnderflowed = false;
	    private int rescalingCount = 0;
	    private int rescalingCountInner = 0;

	    
	    /**
	     * the number of rate categories
	     */
	    protected int categoryCount;

	    /**
	     * an array used to transfer tip partials
	     */
	    protected double[] tipPartials;

	    /**
	     * the BEAGLE library instance
	     */
	    protected Beagle beagle;
	    
	    public Beagle getBeagle() {return beagle;}

	    /**
	     * Flag to specify that the substitution model has changed
	     */
	    protected boolean updateSubstitutionModel;
	    protected boolean storedUpdateSubstitutionModel;

	    /**
	     * Flag to specify that the site model has changed
	     */
	    protected boolean updateSiteModel;
	    protected boolean storedUpdateSiteModel;

	    /**
	     * Flag to specify if site patterns are acertained
	     */

	    private boolean ascertainedSitePatterns = false;

	    public class BufferIndexHelper {
	        /**
	         * @param maxIndexValue the number of possible input values for the index
	         * @param minIndexValue the minimum index value to have the mirrored buffers
	         */
	        BufferIndexHelper(int maxIndexValue, int minIndexValue) {
	            this.maxIndexValue = maxIndexValue;
	            this.minIndexValue = minIndexValue;

	            offsetCount = maxIndexValue - minIndexValue;
	            indexOffsets = new int[offsetCount];
	            storedIndexOffsets = new int[offsetCount];
	        }

	        public int getBufferCount() {
	            return 2 * offsetCount + minIndexValue;
	        }

	        void flipOffset(int i) {
	            if (i >= minIndexValue) {
	                indexOffsets[i - minIndexValue] = offsetCount - indexOffsets[i - minIndexValue];
	            } // else do nothing
	        }

	        public int getOffsetIndex(int i) {
	            if (i < minIndexValue) {
	                return i;
	            }
	            return indexOffsets[i - minIndexValue] + i;
	        }

	        void getIndices(int[] outIndices) {
	            for (int i = 0; i < maxIndexValue; i++) {
	                outIndices[i] = getOffsetIndex(i);
	            }
	        }

	        void storeState() {
	            System.arraycopy(indexOffsets, 0, storedIndexOffsets, 0, indexOffsets.length);

	        }

	        void restoreState() {
	            int[] tmp = storedIndexOffsets;
	            storedIndexOffsets = indexOffsets;
	            indexOffsets = tmp;
	        }

	        private final int maxIndexValue;
	        private final int minIndexValue;
	        private final int offsetCount;

	        private int[] indexOffsets;
	        private int[] storedIndexOffsets;

	    } // class BufferIndexHelper

	    public enum PartialsRescalingScheme {
	        DEFAULT("default"), // whatever our current favourite default is
	        NONE("none"),       // no scaling
	        DYNAMIC("dynamic"), // rescale when needed and reuse scaling factors
	        ALWAYS("always"),   // rescale every node, every site, every time - slow but safe
	        DELAYED("delayed"), // postpone until first underflow then switch to 'always'
	        AUTO("auto");       // BEAGLE automatic scaling - currently playing it safe with 'always'
//	        KICK_ASS("kickAss"),// should be good, probably still to be discovered

	        PartialsRescalingScheme(String text) {
	            this.text = text;
	        }

	        public String getText() {
	            return text;
	        }

	        private final String text;

	        public static PartialsRescalingScheme parseFromString(String text) {
	            for (PartialsRescalingScheme scheme : PartialsRescalingScheme.values()) {
	                if (scheme.getText().compareToIgnoreCase(text) == 0)
	                    return scheme;
	            }
	            return DEFAULT;
	        }
	    }
	    
	    @Override
	    public double [] getPatternLogLikelihoods() {
	        beagle.getSiteLogLikelihoods(patternLogLikelihoods);
			return patternLogLikelihoods.clone();
		}

	}
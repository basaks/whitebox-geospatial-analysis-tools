/*
 * Copyright (C) 2014 Dr. John Lindsay <jlindsay@uoguelph.ca>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
 
import java.awt.event.ActionListener
import java.awt.event.ActionEvent
import java.util.Date
import java.util.ArrayList
import java.util.Queue
import java.util.LinkedList
import java.util.ArrayDeque
import java.text.DecimalFormat
import java.util.stream.IntStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import whitebox.interfaces.WhiteboxPluginHost
import whitebox.geospatialfiles.WhiteboxRaster
import whitebox.geospatialfiles.WhiteboxRasterInfo
import whitebox.geospatialfiles.WhiteboxRasterBase.DataType
import whitebox.geospatialfiles.WhiteboxRasterBase.DataScale
import whitebox.geospatialfiles.ShapeFile
import whitebox.geospatialfiles.shapefile.*
import whitebox.ui.plugin_dialog.ScriptDialog
import whitebox.utilities.StringUtilities
import groovy.transform.CompileStatic
import groovy.time.TimeDuration
import groovy.time.TimeCategory

// The following four variables are required for this 
// script to be integrated into the tool tree panel. 
// Comment them out if you want to remove the script.
def name = "D8PointerParallel"
def descriptiveName = "D8 Pointer (Parallel)"
def description = "Calculates the D8 flow pointer raster."
def toolboxes = ["FlowPointers"]

public class D8PointerParallel implements ActionListener {
	private WhiteboxPluginHost pluginHost
	private ScriptDialog sd;
	private String descriptiveName

	private AtomicInteger numSolved = new AtomicInteger(0)
	private AtomicInteger curProgress = new AtomicInteger(-1)
	
	public D8PointerParallel(WhiteboxPluginHost pluginHost, 
		String[] args, String name, String descriptiveName) {
		this.pluginHost = pluginHost
		this.descriptiveName = descriptiveName
			
		if (args.length > 0) {
			execute(args)
		} else {
			// Create a dialog for this tool to collect user-specified
			// tool parameters.
		 	sd = new ScriptDialog(pluginHost, descriptiveName, this)	
		
			// Specifying the help file will display the html help
			// file in the help pane. This file should be be located 
			// in the help directory and have the same name as the 
			// class, with an html extension.
			sd.setHelpFile(name)
		
			// Specifying the source file allows the 'view code' 
			// button on the tool dialog to be displayed.
			def pathSep = File.separator
			def scriptFile = pluginHost.getResourcesDirectory() + "plugins" + pathSep + "Scripts" + pathSep + name + ".groovy"
			sd.setSourceFile(scriptFile)
			
			// add some components to the dialog
			sd.addDialogFile("Input DEM raster", "Input DEM Raster:", "open", "Raster Files (*.dep), DEP", true, false)
            sd.addDialogFile("Output file", "Output Raster File:", "save", "Raster Files (*.dep), DEP", true, false)
			sd.addDialogCheckBox("Run in parallel mode?", "Run in parallel mode?", true)
			
			// resize the dialog to the standard size and display it
			sd.setSize(800, 400)
			sd.visible = true
		}
	}

	// The CompileStatic annotation can be used to significantly
	// improve the performance of a Groovy script to nearly 
	// that of native Java code.
	@CompileStatic
	private void execute(String[] args) {
		try {

			Date start = new Date()
			
			int progress, oldProgress, i, row, col, rN, cN, dir, n
	  		int[] dX = [ 1, 1, 1, 0, -1, -1, -1, 0 ]
			int[] dY = [ -1, 0, 1, 1, 1, 0, -1, -1 ]
			int[] inflowingVals = [ 4, 5, 6, 7, 0, 1, 2, 3 ]
        	double z, zN, slope, maxSlope
        	double dirVal
        	DecimalFormat df = new DecimalFormat("###.#")
			
			if (args.length != 3) {
				pluginHost.showFeedback("Incorrect number of arguments given to tool.")
				return
			}
			// read the input parameters
			String inputFile = args[0]
			String outputFile = args[1]
			boolean doParallel = Boolean.parseBoolean(args[2])
			
			// read the input image
			WhiteboxRaster dem = new WhiteboxRaster(inputFile, "r")
			//dem.setForceAllDataInMemory(true)
			double nodata = dem.getNoDataValue()
			int rows = dem.getNumberRows()
			int rowsLessOne = rows - 1
			int cols = dem.getNumberColumns()
			int numPixels = rows * cols
			double gridResX = dem.getCellSizeX();
            double gridResY = dem.getCellSizeY();
            double diagGridRes = Math.sqrt(gridResX * gridResX + gridResY * gridResY);
            double[] gridLengths = [diagGridRes, gridResX, diagGridRes, gridResY, diagGridRes, gridResX, diagGridRes, gridResY]

            // calculate the flow direction
            byte[][] flowDir = new byte[rows][cols]
            if (doParallel) {
				double[][] allData = new double[rows + 2][cols]
				for (row = -1; row <= rows; row++) {
					 allData[row + 1] = dem.getRowValues(row)
				}
				IntStream.range(0, rows).parallel().forEach{ 
					double[][] data = new double[3][cols]
					data[0] = allData[it] //dem.getRowValues(it - 1)
					data[1] = allData[it + 1] //dem.getRowValues(it)
					data[2] = allData[it + 2] //dem.getRowValues(it + 1)
					calcFlowDirection(it, data, flowDir, gridLengths, nodata) 
				};
				allData = new double[0][0]
			} else {
	            oldProgress = -1
	            double[] data
				for (row = 0; row < rows; row++) {
					data = dem.getRowValues(row)
					for (col = 0; col < cols; col++) {
						z = data[col] //dem.getValue(row, col)
						if (z != nodata) {
							dir = -1
							maxSlope = -99999999
							for (i = 0; i < 8; i++) {
								zN = dem.getValue(row + dY[i], col + dX[i])
								if (zN != nodata) {
									slope = (z - zN) / gridLengths[i]
									if (slope > maxSlope && slope > 0) {
										maxSlope = slope
										dir = i
									}
								}
							}
							flowDir[row][col] = (byte)dir
						}
						
					}
					progress = (int)(100f * row / rowsLessOne)
					if (progress != oldProgress) {
						pluginHost.updateProgress("Calculating Flow Direction:", progress)
						oldProgress = progress
	
						// check to see if the user has requested a cancellation
						if (pluginHost.isRequestForOperationCancelSet()) {
							pluginHost.showFeedback("Operation cancelled")
							return
						}
					}
				}
			}

			// initialize the output image
			pluginHost.updateProgress("Creating output file:", 0)
			WhiteboxRaster output = new WhiteboxRaster(outputFile, "rw", 
  		  	     inputFile, DataType.FLOAT, nodata)
			output.setPreferredPalette("qual.pal");
            output.setDataScale(DataScale.CATEGORICAL);
            output.setZUnits("dimensionless");
            
			
			int r, c
			double[] data
			for (row = 0; row < rows; row++) {
				data = dem.getRowValues(row)
				for (col = 0; col < cols; col++) {
					if (data[col] != nodata) {
						if (flowDir[row][col] >= 0) {
							//performing a bit shift 
	                        //to assign a flow direction that is 2 to 
	                        //the exponent of i
							dirVal = 1 << flowDir[row][col] 
							output.setValue(row, col, dirVal)
						} else {
							output.setValue(row, col, 0)
						}
					}
				}
				progress = (int)(100f * row / rowsLessOne)
				if (progress != oldProgress) {
					pluginHost.updateProgress("Saving data:", progress)
					oldProgress = progress

					// check to see if the user has requested a cancellation
					if (pluginHost.isRequestForOperationCancelSet()) {
						pluginHost.showFeedback("Operation cancelled")
						return
					}
				}
			}

			// the DEM won't be needed any longer
			dem.close()

			output.addMetadataEntry("Created by the "
	                    + descriptiveName + " tool.")
	        output.addMetadataEntry("Created on " + new Date())
			output.close()

			Date stop = new Date()
			TimeDuration td = TimeCategory.minus( stop, start )
			pluginHost.showFeedback("Elapsed time: $td")
			
			// display the output image
			pluginHost.returnData(outputFile)
	
		} catch (OutOfMemoryError oe) {
            pluginHost.showFeedback("An out-of-memory error has occurred during operation.")
	    } catch (Exception e) {
	    	pluginHost.showFeedback("An error has occurred during operation. See log file for details.")
	        pluginHost.logException("Error in " + descriptiveName, e)
        } finally {
        	// reset the progress bar
        	pluginHost.updateProgress(0)
        }
	}

	private void calcFlowDirection(int row, double[][] data, byte[][] flowDir, double[] gridLengths, double nodata) {
		// check to see if the user has requested a cancellation
		if (pluginHost.isRequestForOperationCancelSet()) {
			return
		}
		
		int[] dX = [ 1, 1, 1, 0, -1, -1, -1, 0 ]
		int[] dY = [ -1, 0, 1, 1, 1, 0, -1, -1 ]
		int cols = flowDir[0].length
        int rows = flowDir.length
        int dir, cN
		double z, zN, maxSlope, slope
		for (int col = 0; col < cols; col++) {
			z = data[1][col]
			if (z != nodata) {
				dir = -1
				maxSlope = -99999999
				for (int i = 0; i < 8; i++) {
					cN = col + dX[i]
					if (cN >= 0 && cN < cols) {
						zN = data[1 + dY[i]][cN]
						if (zN != nodata) {
							slope = (z - zN) / gridLengths[i]
							if (slope > maxSlope && slope > 0) {
								maxSlope = slope
								dir = i
							}
						}
					}
				}
				flowDir[row][col] = (byte)dir
			} else {
				flowDir[row][col] = (byte)-2
			}
			
		}

		int solved = numSolved.incrementAndGet()
		int progress = (int) (100f * solved / (rows - 1))
		if (progress > curProgress.get()) {
			curProgress.incrementAndGet()
			pluginHost.updateProgress("Calculating Flow Directions:", progress)
		}
	}

	@Override
    public void actionPerformed(ActionEvent event) {
    	if (event.getActionCommand().equals("ok")) {
    		final def args = sd.collectParameters()
			sd.dispose()
			final Runnable r = new Runnable() {
            	@Override
            	public void run() {
                	execute(args)
            	}
        	}
        	final Thread t = new Thread(r)
        	t.start()
    	}
    }
}

if (args == null) {
	pluginHost.showFeedback("Plugin arguments not set.")
} else {
	def tdf = new D8PointerParallel(pluginHost, args, name, descriptiveName)
}

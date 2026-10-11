package escuelaing.edu.co.truckdar.load_allocation.service;

import escuelaing.edu.co.truckdar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.CreateFreightLoadRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckdar.load_allocation.model.TruckTypeRequired;

import java.util.List;

public interface IFreightLoadService {
    FreightLoadResponse publishLoad(CreateFreightLoadRequest request);
    FreightLoadResponse assignDriver(Long loadId, AssignDriverRequest request);
    FreightLoadResponse notifyAssignment(Long loadId);
    boolean applyAssignmentNotified(Long loadId, String notifiedDriverId);
    FreightLoadResponse acceptAssignment(Long loadId);
    FreightLoadResponse rejectAssignment(Long loadId);
    FreightLoadResponse timeoutAssignment(Long loadId);
    FreightLoadResponse getLoadById(Long loadId);
    List<FreightLoadResponse> getAvailableLoads(TruckTypeRequired truckType);
    List<FreightLoadResponse> getLoadsByCompany(String generatorCompanyId);
}